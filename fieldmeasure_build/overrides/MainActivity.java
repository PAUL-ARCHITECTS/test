package com.fieldmeasure.ar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.content.Context;
import android.text.TextUtils;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** AR Optional: native ARCore measurement when available, calibrated photo ruler otherwise. */
public class MainActivity extends Activity implements ArMeasureRenderer.Callback {
    private static final int REQ_CAMERA=201, REQ_IMAGE=202, REQ_CSV=203, REQ_DXF=204, REQ_PNG=205, REQ_PLAN_DXF=206, REQ_PLAN_PNG=207;
    private static final int NAVY=Color.rgb(11,18,32), CARD=Color.rgb(24,37,57),
            ACCENT=Color.rgb(25,213,176), PALE=Color.rgb(216,231,241);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<SurveyRecord> records=new ArrayList<>();
    private final List<Geometry.Vec3> pendingArPoints=new ArrayList<>();
    private LinearLayout root, toolbar, controls;
    private FrameLayout viewport;
    private TextView status, summary;
    private Button btnAr,btnPhoto,btnFloor,btnProjects,btnAction,btnUndo,btnSave,btnClear;
    private LinearLayout toolStrip;
    private FloorPlanView floorView;
    private ProjectStore store;
    private ProjectStore.Entry currentProject;
    private String currentPhotoUri="";
    private String viewMode="home";
    private boolean arAreaMode=false;
    private boolean axisSnap=true,endpointSnap=true,gridSnap=true;
    private boolean trackingPreviously=false;
    private String currentArMode="测线";
    private PhotoMeasureView photoView;
    private GLSurfaceView glView;
    private ArOverlay arOverlay;
    private ArMeasureRenderer renderer;
    private Session session;
    private boolean arMode, arActive, glResumed, pendingStart, allowInstallPrompt=true;
    private double pixelPerMetre=0;
    private int lineGroup=1;
    private int arCheckAttempts=0;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(NAVY);getWindow().setNavigationBarColor(NAVY);
        restoreRecords();
        store=new ProjectStore(this);
        buildUi();
        showHome();
    }

    private int dp(float val) {return (int)(val*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable bg(int color,int radius) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private TextView text(String text,int size,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);return t;
    }
    private Button button(String title, boolean main) {
        Button b=new Button(this);b.setText(title);b.setTextSize(13);b.setAllCaps(false);
        b.setTextColor(main?NAVY:PALE);b.setBackground(bg(main?ACCENT:CARD,12));
        b.setPadding(dp(3),0,dp(3),0);
        return b;
    }
    private void rowButton(LinearLayout row,Button b) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(52),1);
        lp.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(b,lp);
    }
    private void buildUi() {
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(NAVY);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;
        });
        TextView title=text("▧  FIELD MEASURE   现场测绘  v1.1",19,Color.WHITE,true);
        title.setPadding(dp(16),dp(8),dp(12),dp(2));root.addView(title,new LinearLayout.LayoutParams(-1,dp(47)));
        title.setOnClickListener(v->showHome());
        TextView caption=text("AR · 照片 · 平面图 · 项目管理   |   离线本地保存",11,PALE,false);
        caption.setPadding(dp(16),0,dp(8),dp(7));root.addView(caption);
        toolbar=new LinearLayout(this);toolbar.setPadding(dp(5),dp(0),dp(5),dp(3));
        btnAr=button("AR 测量",false);btnPhoto=button("照片测量",false);
        btnFloor=button("平面图",false);btnProjects=button("项目 / 历史",false);
        rowButton(toolbar,btnAr);rowButton(toolbar,btnPhoto);
        rowButton(toolbar,btnFloor);rowButton(toolbar,btnProjects);root.addView(toolbar);
        btnAr.setOnClickListener(v->startAr());btnPhoto.setOnClickListener(v->showPhotoMode(true));
        btnFloor.setOnClickListener(v->showFloorMode(true));btnProjects.setOnClickListener(v->showHistory());
        HorizontalScrollView tools=new HorizontalScrollView(this);tools.setHorizontalScrollBarEnabled(false);
        toolStrip=new LinearLayout(this);toolStrip.setPadding(dp(6),dp(1),dp(6),dp(4));
        tools.addView(toolStrip);root.addView(tools,new LinearLayout.LayoutParams(-1,dp(49)));
        viewport=new FrameLayout(this);viewport.setBackgroundColor(CARD);
        root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        status=text("准备就绪",12,PALE,false);status.setPadding(dp(13),dp(5),dp(13),dp(4));
        status.setMaxLines(2);root.addView(status,new LinearLayout.LayoutParams(-1,dp(46)));
        summary=text("本机项目和测量记录",16,Color.WHITE,true);
        summary.setPadding(dp(13),0,dp(13),dp(2));root.addView(summary,new LinearLayout.LayoutParams(-1,dp(35)));
        summary.setOnClickListener(v->showHistory());
        controls=new LinearLayout(this);controls.setPadding(dp(6),0,dp(6),dp(3));
        btnAction=button("＋ 添加点",true);btnUndo=button("撤销",false);
        btnSave=button("保存",false);btnClear=button("清空",false);
        rowButton(controls,btnAction);rowButton(controls,btnUndo);
        rowButton(controls,btnSave);rowButton(controls,btnClear);root.addView(controls);
        LinearLayout bottom=new LinearLayout(this);bottom.setPadding(dp(6),0,dp(6),dp(7));
        Button btnManual=button("＋ 卷尺记录",false);
        Button btnExport=button("⇩ 导出 / 分享",true);
        rowButton(bottom,btnManual);rowButton(bottom,btnExport);root.addView(bottom);
        btnAction.setOnClickListener(v->{
            if("ar".equals(viewMode)){
                if(arActive&&glView!=null)glView.queueEvent(()->renderer.requestPoint());else startAr();
            }else if("photo".equals(viewMode))choosePhoto();
            else if("floor".equals(viewMode)){
                if(floorView!=null)floorView.close();
            }else showFloorMode(true);
        });
        btnUndo.setOnClickListener(v->undo());
        btnSave.setOnClickListener(v->{
            if("ar".equals(viewMode))chooseSaveAr();
            else if("photo".equals(viewMode))savePhotoWorkspace(true);
            else if("floor".equals(viewMode))saveFloorWorkspace(true);
            else showHistory();
        });
        btnClear.setOnClickListener(v->clearPending());
        btnManual.setOnClickListener(v->manualRecord());
        btnExport.setOnClickListener(v->showExportOptions());
    }

    private void activateNav(String type){
        viewMode=type;
        Button[] buttons={btnAr,btnPhoto,btnFloor,btnProjects};
        String[] types={"ar","photo","floor","history"};
        for(int i=0;i<buttons.length;i++){
            boolean active=types[i].equals(type);
            buttons[i].setBackground(bg(active?ACCENT:CARD,12));buttons[i].setTextColor(active?NAVY:PALE);
        }
        controls.setVisibility("history".equals(type)||"home".equals(type)?View.GONE:View.VISIBLE);
        toolStrip.removeAllViews();
    }
    private void tool(String name, boolean selected, Runnable action){
        Button b=button(name,selected);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(Math.max(57,Math.min(110,name.length()*14+28))),dp(38));
        lp.setMargins(dp(3),0,dp(3),0);toolStrip.addView(b,lp);b.setOnClickListener(v->action.run());
    }
    private void rebuildPhotoTools(){
        toolStrip.removeAllViews();
        tool("导入",false,this::choosePhoto);
        String[] options={"校准","长度","折线","面积","角度","标注"};
        for(String kind:options)tool(kind,photoView!=null&&kind.equals(photoView.getMode()),()->{
            if(photoView==null)return;
            if("标注".equals(kind)){askPhotoText();return;}
            photoView.setMode(kind);rebuildPhotoTools();
        });
        tool("闭合/完成",false,()->{if(photoView!=null)photoView.finishPath(true);savePhotoWorkspace(false);});
    }
    private void rebuildFloorTools(){
        toolStrip.removeAllViews();
        String[] options={"墙","房间","门","窗","楼梯","柱","文字","移动","删除"};
        for(String kind:options)tool(kind,floorView!=null&&kind.equals(floorView.getTool()),()->{
            if(floorView!=null)floorView.setTool(kind);rebuildFloorTools();
        });
        tool("闭合",false,()->{if(floorView!=null)floorView.close();});
        tool("吸附",false,()->showSnapOptions());
        tool("重做",false,()->{if(floorView!=null)floorView.redo();});
    }
    private void rebuildArTools(){
        toolStrip.removeAllViews();
        for(String kind:new String[]{"测线","面积","高度"})tool(kind,kind.equals(currentArMode),()->{
            currentArMode=kind;arAreaMode="面积".equals(kind);rebuildArTools();
            note(kind+"模式："+(arAreaMode?"连续选点，点击闭合计算面积":"添加点完成测量"));
        });
        tool("闭合",false,()->{if(pendingArPoints.size()>=3)savePolyline(true);
            else note("至少3个 AR 点才能闭合");});
        tool("吸附说明",false,()->new AlertDialog.Builder(this).setTitle("AR 辅助吸附")
            .setMessage("当前支持附近已测点的距离检测和显式闭合；ARCore 的墙面命中依赖设备与光照。方向/墙角并未自动识别，施工测量仍需现场复核。")
            .setPositiveButton("了解",null).show());
    }
    private void showHome(){
        if(arMode)stopAr();arMode=false;activateNav("home");
        viewport.removeAllViews();ScrollView sc=new ScrollView(this);
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setPadding(dp(14),dp(15),dp(14),dp(18));
        TextView big=text("把现场记录变成可编辑图纸",23,Color.WHITE,true);wrap.addView(big);
        TextView info=text("四种工作模式 · 本机离线保存 · 支持导出\n\n",13,PALE,false);wrap.addView(info);
        addHomeTile(wrap,"◈   AR 空间测量","空间测线 · 面积 · 高度",this::startAr);
        addHomeTile(wrap,"▧   照片测量","比例校准 · 多边形 · 角度",()->showPhotoMode(false));
        addHomeTile(wrap,"▣   平面图绘制","墙 · 门 · 窗 · 楼梯 · 房间",()->showFloorMode(true));
        addHomeTile(wrap,"▤   项目 / 历史","查看 · 重命名 · 删除 · 再编辑",this::showHistory);
        sc.addView(wrap);viewport.addView(sc,new FrameLayout.LayoutParams(-1,-1));
        note("测量是辅助估算，现场施工尺寸需卷尺或激光设备复核");refreshTotals();
    }
    private void addHomeTile(LinearLayout wrap,String title,String subtitle,Runnable action){
        LinearLayout block=new LinearLayout(this);block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(18),dp(15),dp(15),dp(15));block.setBackground(bg(CARD,14));
        TextView a=text(title,19,Color.WHITE,true);TextView b=text(subtitle,13,PALE,false);
        block.addView(a);block.addView(b);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(85));
        lp.setMargins(0,0,0,dp(9));wrap.addView(block,lp);block.setOnClickListener(v->action.run());
    }

    private void note(String message) {status.setText(message);}
    private void refreshTotals() {
        double total=0;
        for(SurveyRecord r:records) if("长度".equals(r.kind)) total+=r.value;
        if("floor".equals(viewMode)&&floorView!=null){
            summary.setText(String.format(Locale.CHINA,"墙长 %.2f m · 封闭面积 %.2f m²",floorView.getWallLength(),floorView.getArea()));
        } else if("ar".equals(viewMode)&&!pendingArPoints.isEmpty()){
            summary.setText(String.format(Locale.CHINA,"AR %d 点 · 测线 %.3f m",pendingArPoints.size(),Geometry.pathLength(pendingArPoints,false)));
        } else summary.setText(String.format(Locale.CHINA,"%d 条记录 · %d 个项目 · 测线 %.2f m",records.size(),store==null?0:store.all().size(),total));
    }

    private void showPhotoMode(boolean saveAr) {
        if(saveAr&&arActive&&pendingArPoints.size()>=2)savePolyline(false);
        stopAr();arMode=false;pendingStart=false;
        activateNav("photo");viewport.removeAllViews();
        if(photoView==null){
            photoView=new PhotoMeasureView(this);
            photoView.setListener(new PhotoMeasureView.Listener(){
                @Override public void onPair(PointF a,PointF b){handlePhotoPair(a,b);}
                @Override public void onMetric(String kind,double value,String unit){
                    addRecord(new SurveyRecord("照片(近似)",kind+"-"+(records.size()+1),kind,value,unit,
                        "按单平面标尺推算，透视及镜头误差未知，施工前复核"));
                    savePhotoWorkspace(false);
                }
                @Override public void onMessage(String m){note(m);}
            });
        }
        if(photoView.getParent()!=null)((ViewGroup)photoView.getParent()).removeView(photoView);
        viewport.addView(photoView,new FrameLayout.LayoutParams(-1,-1));
        btnAction.setText("导入照片");btnUndo.setText("撤销");btnSave.setText("保存项目");btnClear.setText("清空描绘");
        rebuildPhotoTools();note("导入照片 → 选校准工具 → 按实际尺寸校准（同一平面）");refreshTotals();
    }
    private void showFloorMode(boolean resetWhenNew){
        if(arActive&&pendingArPoints.size()>=2)savePolyline(false);
        stopAr();arMode=false;pendingStart=false;activateNav("floor");viewport.removeAllViews();
        if(resetWhenNew||floorView==null){floorView=new FloorPlanView(this);currentProject=null;}
        floorView.setListener(new FloorPlanView.Listener(){
            @Override public void onMessage(String s){note(s);}
            @Override public void onText(PointF at){
                EditText input=new EditText(MainActivity.this);input.setSingleLine();input.setHint("例如 客厅 / 楼梯间");
                new AlertDialog.Builder(MainActivity.this).setTitle("添加文字标注").setView(input)
                    .setPositiveButton("添加",(d,w)->floorView.addLabel(at,input.getText().toString()))
                    .setNegativeButton("取消",null).show();
            }
            @Override public void onChanged(){refreshTotals();if(currentProject!=null&&"平面图".equals(currentProject.type)){
                currentProject.data=floorView.toJson();store.upsert(currentProject);
            }}
        });
        if(floorView.getParent()!=null)((ViewGroup)floorView.getParent()).removeView(floorView);
        viewport.addView(floorView,new FrameLayout.LayoutParams(-1,-1));
        btnAction.setText("闭合区域");btnUndo.setText("撤销");btnSave.setText("保存图纸");btnClear.setText("清空画布");
        rebuildFloorTools();note("点选墙线端点；靠近已有端点自动吸附。双指缩放，移动工具平移");refreshTotals();
    }
    private void saveFloorWorkspace(boolean askName){
        if(floorView==null)return;
        if(currentProject==null||!"平面图".equals(currentProject.type)){
            currentProject=new ProjectStore.Entry("平面图", "平面图-"+nowTitle(),floorView.toJson());
        }
        if(askName){
            EditText title=new EditText(this);title.setSingleLine();title.setText(currentProject.name);title.selectAll();
            new AlertDialog.Builder(this).setTitle("保存平面图项目").setView(title)
                .setPositiveButton("保存",(d,w)->{
                    currentProject.name=nonempty(title.getText().toString(),currentProject.name);
                    currentProject.data=floorView.toJson();store.upsert(currentProject);
                    note("已保存："+currentProject.name);refreshTotals();
                }).setNegativeButton("取消",null).show();
        }else{currentProject.data=floorView.toJson();store.upsert(currentProject);refreshTotals();}
    }
    private void savePhotoWorkspace(boolean askName){
        if(photoView==null||!photoView.hasPhoto()){note("请先导入照片");return;}
        if(currentProject==null||!"照片".equals(currentProject.type))
            currentProject=new ProjectStore.Entry("照片","照片测量-"+nowTitle(),"{}");
        Runnable save=()->{
            try{
                JSONObject obj=new JSONObject();obj.put("uri",currentPhotoUri);obj.put("drawing",new JSONObject(photoView.toJson()));
                currentProject.data=obj.toString();store.upsert(currentProject);refreshTotals();
                note("已保存照片测量项目");
            }catch(Exception e){note("项目保存失败："+e.getMessage());}
        };
        if(askName){EditText title=new EditText(this);title.setSingleLine();title.setText(currentProject.name);title.selectAll();
            new AlertDialog.Builder(this).setTitle("保存照片测量项目").setView(title)
                .setPositiveButton("保存",(d,w)->{currentProject.name=nonempty(title.getText().toString(),currentProject.name);save.run();})
                .setNegativeButton("取消",null).show();
        }else save.run();
    }
    private String nowTitle(){return new SimpleDateFormat("MM-dd_HHmm",Locale.US).format(new Date());}
    private String nonempty(String raw,String fallback){return raw.trim().isEmpty()?fallback:raw.trim();}
    private void askPhotoText(){
        if(photoView==null||!photoView.hasPhoto()){note("先导入照片");return;}
        EditText edit=new EditText(this);edit.setHint("输入标注，如 门洞 900mm");
        new AlertDialog.Builder(this).setTitle("照片文字标注").setView(edit)
            .setPositiveButton("添加",(d,w)->{photoView.addTextAtCenter(edit.getText().toString());savePhotoWorkspace(false);})
            .setNegativeButton("取消",null).show();
    }
    private void showSnapOptions(){
        boolean[] checked={gridSnap,axisSnap,endpointSnap};String[] names={"0.1m 网格吸附","水平 / 垂直正交锁定","端点吸附（0.18m）"};
        new AlertDialog.Builder(this).setTitle("平面图吸附设置")
            .setMultiChoiceItems(names,checked,(d,index,isChecked)->checked[index]=isChecked)
            .setPositiveButton("应用",(d,w)->{gridSnap=checked[0];axisSnap=checked[1];endpointSnap=checked[2];
                if(floorView!=null)floorView.setSnap(gridSnap,axisSnap,endpointSnap);})
            .setNegativeButton("取消",null).show();
    }

    private void choosePhoto() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("image/*");
        try {startActivityForResult(intent,REQ_IMAGE);}
        catch(Exception e) {note("无法打开照片选择器："+e.getMessage());}
    }

    private void handlePhotoPair(PointF a,PointF b) {
        double pixels=Math.hypot(a.x-b.x,a.y-b.y);
        if(pixels<3){note("两个点距离太近，请重试");return;}
        if(pixelPerMetre<=0||PhotoMeasureView.CAL.equals(photoView.getMode())) {
            EditText field=numberField("例如 1.20（米）");
            new AlertDialog.Builder(this).setTitle("校准：已知线段的真实长度（m）")
                    .setMessage("请选择与待测物同一平面、相近深度的已知长度。\n注意：照片透视会产生误差。")
                    .setView(field)
                    .setNegativeButton("取消",(d,w)->{})
                    .setPositiveButton("确定",(d,w)->{
                        Double metres=positive(field);
                        if(metres==null)return;
                        pixelPerMetre=pixels/metres;
                        photoView.setCalibration(a,b,metres);
                        photoView.setMode(PhotoMeasureView.LEN);
                        rebuildPhotoTools();savePhotoWorkspace(false);
                        note(String.format(Locale.CHINA,"校准成功：%.2f 像素/米；可测线、折线、面积和角度",pixelPerMetre));
                    }).show();
        } else {
            double metres=pixels/pixelPerMetre;
            photoView.addLine(a,b,metres);
            addRecord(new SurveyRecord("照片(近似)","照片测线"+(records.size()+1),"长度",metres,"m","同一平面比例推算，未经透视校正"));
            savePhotoWorkspace(false);
            note(String.format(Locale.CHINA,"照片估算长度 %.3f m（不可直接用于施工）",metres));
        }
    }

    private void resetCalibration() {
        pixelPerMetre=0;
        photoView.clearCalibration();
        note("比例校准已清除；接下来点选参考线的两个端点");
    }

    private EditText numberField(String hint) {
        EditText e=new EditText(this);e.setSingleLine();e.setHint(hint);e.setPadding(dp(22),dp(12),dp(22),dp(12));
        e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        return e;
    }
    private Double positive(EditText text) {
        try {
            double v=Double.parseDouble(text.getText().toString().trim());
            if(!Double.isNaN(v)&&!Double.isInfinite(v)&&v>0&&v<100000)return v;
        }catch(Exception ignored){}
        Toast.makeText(this,"请输入有效的正数（米）",Toast.LENGTH_LONG).show();return null;
    }

    private void manualRecord() {
        EditText field=numberField("卷尺实测长度（米）");
        new AlertDialog.Builder(this).setTitle("记录卷尺实测长度").setView(field)
                .setPositiveButton("保存",(dialog,which)->{
                    Double metres=positive(field);if(metres==null)return;
                    addRecord(new SurveyRecord("卷尺人工","实测段"+(records.size()+1),"长度",metres,"m","现场人工输入，未做独立复核"));
                    note(String.format(Locale.CHINA,"已保存人工实测 %.3f m",metres));
                }).setNegativeButton("取消",null).show();
    }

    private void undo() {
        if("floor".equals(viewMode)){if(floorView!=null)floorView.undo();return;}
        if(!"photo".equals(viewMode)&&!arMode)return;
        if(arMode) {
            if(glView!=null&&renderer!=null)glView.queueEvent(()->renderer.undoPoint());
            note("已撤销最后一个 AR 点");
        } else {
            int undone=photoView.undo();
            if(undone==2) {
                // The view removed an actual photo line, so its saved measurement must also be removed.
                for(int i=records.size()-1;i>=0;i--) {
                    if(records.get(i).source.startsWith("照片")) {records.remove(i);persistRecords();break;}
                }
            }
            if(undone!=0) {refreshTotals();savePhotoWorkspace(false);note(undone==1?"已取消待选的照片端点":"已撤销照片标注");}
        }
    }

    private void clearPending() {
        if("floor".equals(viewMode)){
            if(floorView!=null)new AlertDialog.Builder(this).setTitle("清空当前平面图？")
                .setMessage("清空后可使用撤销恢复。已保存项目会自动更新。")
                .setPositiveButton("清空",(d,w)->floorView.clear()).setNegativeButton("取消",null).show();
            return;
        }
        if(arMode) {
            if(glView!=null&&renderer!=null)glView.queueEvent(()->renderer.reset());
            note("当前 AR 测线已清空；已保存的记录仍保留");
        } else if(photoView!=null) {
            photoView.clearLines();savePhotoWorkspace(false);note("照片标注已清空；历史测量数值仍保留");
        }
    }

    private void startAr() {
        if(Build.VERSION.SDK_INT<24) {
            fallback("Android 7.0 以下不支持 ARCore，但可以使用照片/人工测量");
            return;
        }
        arMode=true;
        activateNav("ar");rebuildArTools();
        btnAction.setText("＋ 添加点");btnUndo.setText("撤销");btnSave.setText("保存测组");btnClear.setText("清空测线");
        if(arActive)return;
        pendingStart=true;
        ArCoreApk.Availability availability;
        try {availability=ArCoreApk.getInstance().checkAvailability(this);}
        catch(Exception|LinkageError e) {fallback("无法检测 ARCore："+e.getClass().getSimpleName());return;}
        if(availability.isTransient()) {
            if(++arCheckAttempts>20) {
                fallback("ARCore 设备检测超时；照片测量仍可正常使用");
                return;
            }
            note("正在检测 ARCore 设备支持情况…");
            handler.postDelayed(()->{if(pendingStart&&!arActive)startAr();},400);
            return;
        }
        arCheckAttempts=0;
        if(!availability.isSupported()) {fallback("此设备不支持 ARCore，已切换到照片测量");return;}
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);return;
        }
        try {
            ArCoreApk.InstallStatus install=ArCoreApk.getInstance().requestInstall(this,allowInstallPrompt);
            if(install==ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                allowInstallPrompt=false;note("请安装/更新 Google Play AR 服务，完成后将继续");return;
            }
            session=new Session(this);
            Config c=session.getConfig();
            c.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
            if(session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) c.setDepthMode(Config.DepthMode.AUTOMATIC);
            session.configure(c);
            renderer=new ArMeasureRenderer(session,this,this);
            glView=new GLSurfaceView(this);
            glView.setEGLContextClientVersion(2);
            glView.setPreserveEGLContextOnPause(true);
            glView.setRenderer(renderer);
            glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
            arOverlay=new ArOverlay(this);
            viewport.removeAllViews();
            viewport.addView(glView,new FrameLayout.LayoutParams(-1,-1));
            viewport.addView(arOverlay,new FrameLayout.LayoutParams(-1,-1));
            pendingStart=false;arActive=true;
            session.resume();glView.onResume();glResumed=true;
            note("缓慢移动手机建立追踪 → 准星锁定平面 → 点选测量端点");
        } catch(Exception|LinkageError e) {
            stopAr();fallback("AR 启动失败："+e.getClass().getSimpleName());
        }
    }

    private void fallback(String message) {
        pendingStart=false;allowInstallPrompt=true;arCheckAttempts=0;showPhotoMode(false);note(message);
        Toast.makeText(this,message,Toast.LENGTH_LONG).show();
    }

    private void stopAr() {
        if(glView!=null&&glResumed) {try {glView.onPause();}catch(Exception ignored){}glResumed=false;}
        if(session!=null) {
            try {session.pause();}catch(Exception ignored){}
            try {session.close();}catch(Exception ignored){}
        }
        session=null;renderer=null;glView=null;arOverlay=null;arActive=false;trackingPreviously=false;
        pendingArPoints.clear();
    }

    @Override public void onProjected(List<ArOverlay.Dot> dots,List<Double> distances,boolean tracking) {
        runOnUiThread(()->{
            if(arOverlay!=null)arOverlay.setDrawing(dots,distances,tracking);
            if(arActive&&tracking!=trackingPreviously){
                trackingPreviously=tracking;
                note(tracking?"✓ 空间追踪已建立 · 准星绿色，可在检测到的表面打点":"追踪中断 · 缓慢移动手机等待重定位");
                if(tracking)try{status.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);}catch(Exception ignored){}
            }
        });
    }
    @Override public void onPointsChanged(List<Geometry.Vec3> points) {
        runOnUiThread(()->{
            if(!arActive)return;
            pendingArPoints.clear();pendingArPoints.addAll(points);
            if(points.isEmpty()) refreshTotals();
            else {
                double current=Geometry.pathLength(points,false);
                if("高度".equals(currentArMode)&&points.size()>=2){
                    double vertical=Math.abs(points.get(points.size()-1).y-points.get(0).y);
                    summary.setText(String.format(Locale.CHINA,"两点高差 %.3f m  ·  斜距 %.3f m",vertical,current));
                }else if(arAreaMode&&points.size()>=3){
                    summary.setText(String.format(Locale.CHINA,"%d 点 · 待闭合面积 %.3f m²",points.size(),Geometry.polygonArea(points)));
                }else summary.setText(String.format(Locale.CHINA,"当前 AR %d 点 · 测线 %.3f m",points.size(),current));
            }
        });
    }
    @Override public void onStatus(String message) {runOnUiThread(()->{
        if(arMode){note(message);if(message.contains("已标记")||message.contains("吸附")){
            try{status.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);}catch(Exception ignored){}
        }}
    });}

    private void chooseSaveAr() {
        if(!arActive||pendingArPoints.size()<2) {note("至少添加两个 AR 点，才可保存测线");return;}
        if("高度".equals(currentArMode)){saveHeight();return;}
        if(pendingArPoints.size()<3) {savePolyline(false);return;}
        if(arAreaMode){savePolyline(true);return;}
        new AlertDialog.Builder(this).setTitle("如何保存这组 AR 测点？")
                .setItems(new String[]{"保存开放测线（长度）","闭合多边形（周长 + 面积）"},
                        (dialog,choice)->savePolyline(choice==1)).show();
    }
    private void saveHeight(){
        if(pendingArPoints.size()<2){note("高度需至少两个测点");return;}
        Geometry.Vec3 a=pendingArPoints.get(0),b=pendingArPoints.get(pendingArPoints.size()-1);
        double height=Math.abs(a.y-b.y),diagonal=Geometry.distance(a,b);
        String title="AR高差"+(lineGroup++);
        records.add(new SurveyRecord("ARCore",title,"高度",height,"m","两点世界坐标Y高差；施工前复核"));
        records.add(new SurveyRecord("ARCore",title+" 参考斜距","长度",diagonal,"m",
            "局部XYZ(m): "+xyz(a)+" -> "+xyz(b)+"; 高差测线"));
        persistRecords();
        try{
            JSONArray pts=new JSONArray();pts.put(new JSONArray().put(a.x).put(a.y).put(a.z));
            pts.put(new JSONArray().put(b.x).put(b.y).put(b.z));
            JSONObject json=new JSONObject().put("points",pts).put("length",diagonal).put("height",height);
            store.upsert(new ProjectStore.Entry("AR",title,json.toString()));
        }catch(Exception ignored){}
        refreshTotals();note(String.format(Locale.CHINA,"已保存高差 %.3f m（两端点Y方向）",height));
        if(glView!=null&&renderer!=null)glView.queueEvent(()->renderer.reset());
    }
    private void savePolyline(boolean closed) {
        List<Geometry.Vec3> pts=new ArrayList<>(pendingArPoints);
        if(pts.size()<2)return;
        String title="AR测组"+(lineGroup++);
        for(int i=1;i<pts.size();i++)
            records.add(new SurveyRecord("ARCore",title+" "+i+"-"+(i+1),"长度",
                    Geometry.distance(pts.get(i-1),pts.get(i)),"m",
                    "局部XYZ(m): "+xyz(pts.get(i-1))+" -> "+xyz(pts.get(i))+"; 施工前卷尺复核"));
        if(closed&&pts.size()>=3) {
            records.add(new SurveyRecord("ARCore",title+" 闭合边","长度",
                    Geometry.distance(pts.get(pts.size()-1),pts.get(0)),"m","闭合段"));
            double warped=Geometry.maxOutOfPlane(pts);
            records.add(new SurveyRecord("ARCore",title+" 面积","面积",Geometry.polygonArea(pts),"m²",
                    warped>0.05?"警告：顶点不共面，最大偏离 "+String.format(Locale.US,"%.3f",warped)+" m":"假设点按边界顺序且共面"));
        }
        persistRecords();refreshTotals();
        try{
            JSONArray arpoints=new JSONArray();for(Geometry.Vec3 q:pts)arpoints.put(new JSONArray().put(q.x).put(q.y).put(q.z));
            JSONObject data=new JSONObject().put("closed",closed).put("points",arpoints)
                .put("length",Geometry.pathLength(pts,closed)).put("area",closed?Geometry.polygonArea(pts):0);
            store.upsert(new ProjectStore.Entry("AR",title,data.toString()));
        }catch(Exception ignored){}
        note("已保存 "+title+(closed?"（含闭合面积）":"")+"，可到项目 / 历史查看");
        if(glView!=null&&renderer!=null)glView.queueEvent(()->renderer.reset());
    }

    private static String xyz(Geometry.Vec3 p) {
        return String.format(Locale.US,"(%.4f %.4f %.4f)",p.x,p.y,p.z);
    }
    private void addRecord(SurveyRecord r) {records.add(r);persistRecords();refreshTotals();}
    private void exportCsv() {
        if(arActive&&pendingArPoints.size()>=2) {
            new AlertDialog.Builder(this).setTitle("有未保存的 AR 测线")
                    .setMessage("是否先把当前 AR 测线作为开放测线保存，再导出？")
                    .setPositiveButton("保存并导出",(d,w)->{savePolyline(false);createCsvFile();})
                    .setNegativeButton("直接导出已保存记录",(d,w)->createCsvFile()).show();
        } else createCsvFile();
    }
    private void showHistory() {
        if("floor".equals(viewMode)&&floorView!=null&&floorView.hasContent())saveFloorWorkspace(false);
        if("photo".equals(viewMode)&&photoView!=null&&photoView.hasPhoto())savePhotoWorkspace(false);
        if(arActive&&pendingArPoints.size()>=2)savePolyline(false);
        stopAr();arMode=false;pendingStart=false;activateNav("history");viewport.removeAllViews();
        ScrollView scroll=new ScrollView(this);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12),dp(10),dp(12),dp(16));
        TextView title=text("本机保存的项目",21,Color.WHITE,true);
        list.addView(title,new LinearLayout.LayoutParams(-1,dp(50)));
        LinearLayout actions=new LinearLayout(this);
        Button fresh=button("＋ 新建平面图",true),old=button("测量明细 "+records.size()+" 条",false);
        rowButton(actions,fresh);rowButton(actions,old);list.addView(actions);
        fresh.setOnClickListener(v->showFloorMode(true));old.setOnClickListener(v->showRecordHistory());
        List<ProjectStore.Entry> entries=store.all();
        entries.sort((x,y)->Long.compare(y.modified,x.modified));
        if(entries.isEmpty()){
            TextView empty=text("还没有项目。测量或绘图后点击「保存」。",14,PALE,false);
            empty.setPadding(dp(12),dp(25),dp(12),dp(30));list.addView(empty);
        }
        for(ProjectStore.Entry entry:entries){
            LinearLayout block=new LinearLayout(this);block.setOrientation(LinearLayout.VERTICAL);
            block.setBackground(bg(CARD,13));block.setPadding(dp(15),dp(10),dp(15),dp(9));
            TextView name=text(("平面图".equals(entry.type)?"▣  ":"照片".equals(entry.type)?"▧  ":"◈  ")+entry.name,16,Color.WHITE,true);
            TextView details=text(entry.type+"  ·  "+new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(new Date(entry.modified))+
                "  ·  点击打开 / 长按管理",12,PALE,false);
            block.addView(name);block.addView(details);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(68));lp.setMargins(0,0,0,dp(8));list.addView(block,lp);
            block.setOnClickListener(v->openProject(entry));
            block.setOnLongClickListener(v->{projectActions(entry);return true;});
        }
        scroll.addView(list);viewport.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        note("点击项目继续编辑；长按项目可重命名、删除或查看信息");refreshTotals();
    }
    private void openProject(ProjectStore.Entry entry){
        if("平面图".equals(entry.type)){
            showFloorMode(true);currentProject=entry;floorView.loadJson(entry.data);
            note("已打开可编辑平面图："+entry.name);refreshTotals();
        }else if("照片".equals(entry.type)){
            showPhotoMode(false);currentProject=entry;
            try{
                JSONObject obj=new JSONObject(entry.data);String image=obj.optString("uri","");
                currentPhotoUri=image;
                if(image.isEmpty()){note("项目缺少原图链接，标注暂不能恢复");return;}
                loadPhotoFromUri(Uri.parse(image));
                JSONObject drawing=obj.optJSONObject("drawing");if(drawing!=null){photoView.loadJson(drawing.toString());
                    pixelPerMetre=photoView.getPixelPerMetre();rebuildPhotoTools();}
                note("已打开照片标注："+entry.name);
            }catch(Exception e){note("恢复照片项目失败："+e.getMessage());}
        }else {
            try{
                JSONObject obj=new JSONObject(entry.data);
                double length=obj.optDouble("length"),area=obj.optDouble("area");
                JSONArray pts=obj.optJSONArray("points");
                new AlertDialog.Builder(this).setTitle(entry.name)
                    .setMessage(String.format(Locale.CHINA,"AR 测组（历史只读）\n点位：%d\n测线/周长：%.3f m\n闭合面积：%.3f m²\n\n坐标为原 AR 会话局部坐标；不能恢复到新相机画面。",
                        pts==null?0:pts.length(),length,area))
                    .setPositiveButton("返回项目",(d,w)->showHistory()).show();
            }catch(Exception e){note("无法查看历史测组");showHistory();}
        }
    }
    private void projectActions(ProjectStore.Entry entry){
        new AlertDialog.Builder(this).setTitle(entry.name).setItems(new String[]{"打开 / 编辑","重命名","删除"},(d,idx)->{
            if(idx==0)openProject(entry);
            else if(idx==1){
                EditText field=new EditText(this);field.setSingleLine();field.setText(entry.name);field.selectAll();
                new AlertDialog.Builder(this).setTitle("重命名项目").setView(field)
                    .setPositiveButton("保存",(dialog,w)->{
                        entry.name=nonempty(field.getText().toString(),entry.name);store.upsert(entry);showHistory();
                    }).setNegativeButton("取消",null).show();
            }else new AlertDialog.Builder(this).setTitle("删除此项目？")
                .setMessage("删除后无法从应用内恢复；不会删除手机原始照片。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(dialog,w)->{
                    store.remove(entry.id);if(currentProject!=null&&currentProject.id.equals(entry.id))currentProject=null;showHistory();
                }).show();
        }).setNegativeButton("取消",null).show();
    }
    private void showRecordHistory(){
        if(records.isEmpty()){note("还没有测量数值记录");return;}
        int length=Math.min(records.size(),150);String[] labels=new String[length];
        for(int i=0;i<length;i++){
            int n=records.size()-1-i;SurveyRecord r=records.get(n);
            labels[i]=String.format(Locale.CHINA,"%d  %s  %.3f %s · %s",n+1,r.name,r.value,r.unit,r.source);
        }
        new AlertDialog.Builder(this).setTitle("测量明细（最近 "+length+" 条）")
            .setItems(labels,(d,choice)->{
                int n=records.size()-1-choice;SurveyRecord r=records.get(n);
                new AlertDialog.Builder(this).setTitle("测量明细操作")
                    .setMessage(r.name+"\n"+String.format(Locale.CHINA,"%.3f %s",r.value,r.unit)+"\n"+r.note)
                    .setNegativeButton("返回",null)
                    .setPositiveButton("删除单条",(dialog,w)->{records.remove(n);persistRecords();refreshTotals();showHistory();}).show();
            }).setNegativeButton("返回",null).show();
    }
    private void showExportOptions(){
        if("floor".equals(viewMode)){
            if(floorView==null||!floorView.hasContent()){note("请先绘制或打开平面图");return;}
            new AlertDialog.Builder(this).setTitle("导出平面图")
                .setItems(new String[]{"DXF 2D CAD 分层线稿（米）","PNG 平面图视图"},(d,which)->{
                    saveFloorWorkspace(false);
                    createDocument(which==0?"application/dxf":"image/png",
                        "平面图_"+nowTitle()+(which==0?".dxf":".png"),which==0?REQ_PLAN_DXF:REQ_PLAN_PNG);
                }).show();return;
        }
        if("photo".equals(viewMode)){
            new AlertDialog.Builder(this).setTitle("导出照片测绘")
                .setItems(new String[]{"PNG 带标注照片","CSV 所有测量数值"},(d,which)->{
                    if(which==0)createPhotoFile();else exportCsv();
                }).show();return;
        }
        new AlertDialog.Builder(this).setTitle("导出测量成果")
            .setItems(new String[]{"CSV 测量记录（Excel / WPS）","DXF AR 三维线段（会话局部坐标）",
                "PNG 当前标注照片"},(d,index)->{
                if(index==0)exportCsv();else if(index==1)createDxfFile();else createPhotoFile();
            }).setNegativeButton("取消",null).show();
    }
    private void createDxfFile(){
        if(DxfExporter.segmentCount(records)==0){
            note("DXF 仅导出含三维坐标的已保存 AR 线段；人工与照片长度没有空间坐标");
            return;
        }
        createDocument("application/dxf","现场测绘_AR局部线段.dxf",REQ_DXF);
    }
    private void createPhotoFile(){
        if(arMode||photoView==null||!photoView.hasPhoto()){
            note("请先进入照片模式并导入一张现场照片");return;
        }
        createDocument("image/png","现场照片_测量标注.png",REQ_PNG);
    }
    private void createDocument(String mime,String name,int code){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE,name);
        try {startActivityForResult(i,code);}catch(Exception e){note("无法打开文件保存器："+e.getMessage());}
    }
    private void createCsvFile() {
        if(records.isEmpty()) {note("暂无记录，请先添加测线或人工数据");return;}
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE,"现场测绘_"+System.currentTimeMillis()+".csv");
        try {startActivityForResult(i,REQ_CSV);}
        catch(Exception e){note("无法打开文件保存器："+e.getMessage());}
    }

    private void loadPhotoFromUri(Uri uri) throws Exception {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}
        int sample=1;while(bounds.outWidth/sample>2800||bounds.outHeight/sample>2800)sample*=2;
        BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=sample;
        Bitmap b;try(InputStream in=getContentResolver().openInputStream(uri)){
            b=BitmapFactory.decodeStream(in,null,opts);
        }
        if(b==null)throw new IllegalArgumentException("照片解码失败");
        try(InputStream in=getContentResolver().openInputStream(uri)){
            ExifInterface exif=new ExifInterface(in);
            int direction=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
            Matrix m=new Matrix();
            switch(direction){
                case ExifInterface.ORIENTATION_ROTATE_90:m.postRotate(90);break;
                case ExifInterface.ORIENTATION_ROTATE_180:m.postRotate(180);break;
                case ExifInterface.ORIENTATION_ROTATE_270:m.postRotate(270);break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:m.postScale(-1,1);break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:m.postScale(1,-1);break;
                case ExifInterface.ORIENTATION_TRANSPOSE:m.postRotate(90);m.postScale(-1,1);break;
                case ExifInterface.ORIENTATION_TRANSVERSE:m.postRotate(270);m.postScale(-1,1);break;
            }
            if(!m.isIdentity()){
                Bitmap rotated=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);
                if(rotated!=b){b.recycle();b=rotated;}
            }
        }catch(Exception ignored){}
        pixelPerMetre=0;
        if(photoView!=null){photoView.setPhoto(b);photoView.setMode(PhotoMeasureView.CAL);rebuildPhotoTools();}
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(requestCode==REQ_IMAGE) {
            try {
                currentProject=null;
                currentPhotoUri=uri.toString();
                try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
                loadPhotoFromUri(uri);
                note("照片已导入。请选择校准工具，标记真实参考尺寸");
            }catch(Exception e){note("读取照片失败："+e.getMessage());}
        } else if(requestCode==REQ_PLAN_DXF||requestCode==REQ_PLAN_PNG){
            try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new IllegalStateException("无法写入文件");
                if(requestCode==REQ_PLAN_DXF)out.write(floorView.exportDxf().getBytes(StandardCharsets.US_ASCII));
                else {Bitmap bmp=floorView.capture();if(bmp==null)throw new IllegalStateException("无可导出画布");
                    try{if(!bmp.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("PNG 写入失败");}
                    finally{bmp.recycle();}}
                note("平面图已导出到选择的位置。DXF 为手动描绘参考线稿，非施工测绘成果");
            }catch(Exception e){note("导出失败："+e.getMessage());}
        } else if(requestCode==REQ_CSV) {
            try(OutputStream stream=getContentResolver().openOutputStream(uri)) {
                if(stream==null)throw new IllegalStateException("无法打开输出文件");
                StringBuilder csv=new StringBuilder("\uFEFF序号,时间,来源,名称,类型,数值,单位,备注\r\n");
                for(int n=0;n<records.size();n++)csv.append(records.get(n).toCsv(n+1));
                stream.write(csv.toString().getBytes(StandardCharsets.UTF_8));
                note("CSV 已导出到所选位置，共 "+records.size()+" 条记录");
            }catch(Exception e){note("导出失败："+e.getMessage());}
        } else if(requestCode==REQ_DXF){
            try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new IllegalStateException("无法写入 DXF");
                out.write(DxfExporter.export(records).getBytes(StandardCharsets.US_ASCII));
                note("DXF 已导出："+DxfExporter.segmentCount(records)+" 条 AR 三维线段（局部坐标，单位米）");
            }catch(Exception e){note("DXF 导出失败："+e.getMessage());}
        } else if(requestCode==REQ_PNG){
            try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new IllegalStateException("无法写入图片");
                Bitmap image=photoView.captureAnnotated();
                if(image==null)throw new IllegalStateException("没有照片可以导出");
                try {if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("PNG 编码失败");}
                finally{image.recycle();}
                note("带标注的照片已保存到选择的位置");
            }catch(Exception e){note("图片保存失败："+e.getMessage());}
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==REQ_CAMERA) {
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startAr();
            else fallback("未取得相机权限，已进入照片模式");
        }
    }
    private void persistRecords() {
        try {
            JSONArray array=new JSONArray();
            for(SurveyRecord r:records) {
                JSONObject j=new JSONObject();
                j.put("src",r.source);j.put("name",r.name);j.put("kind",r.kind);
                j.put("value",r.value);j.put("unit",r.unit);j.put("note",r.note);j.put("ts",r.timestamp);
                array.put(j);
            }
            getPreferences(MODE_PRIVATE).edit().putString("records",array.toString()).apply();
        } catch(Exception ignored) {}
    }
    private void restoreRecords() {
        try {
            JSONArray array=new JSONArray(getPreferences(MODE_PRIVATE).getString("records","[]"));
            for(int i=0;i<array.length();i++) {
                JSONObject j=array.getJSONObject(i);
                records.add(new SurveyRecord(j.getString("src"),j.getString("name"),j.getString("kind"),
                        j.getDouble("value"),j.getString("unit"),j.getString("note"),j.getLong("ts")));
            }
            lineGroup=records.size()+1;
        }catch(Exception ignored){}
    }
    @Override protected void onPause() {
        super.onPause();
        if(arActive&&glResumed) {
            glView.onPause();glResumed=false;
            try {session.pause();}catch(Exception ignored){}
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if(arActive&&!glResumed) {
            try {session.resume();glView.onResume();glResumed=true;}
            catch(Exception e){note("无法恢复AR摄像头："+e.getMessage());}
        } else if(pendingStart&&!arActive)handler.postDelayed(this::startAr,250);
    }
    @Override protected void onDestroy() {stopAr();super.onDestroy();}
}