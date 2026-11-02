package com.fieldmeasure.ar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Offline metric 2D plan sketcher. User-created geometry only: NO inferred/AI wall recognition. */
public final class FloorPlanView extends View {
    public interface Listener { void onMessage(String m);void onText(PointF at);void onChanged(); }
    public static final String WALL="墙", DOOR="门", WINDOW="窗", STAIRS="楼梯", COLUMN="柱", ROOM="房间", TEXT="文字", PAN="移动", ERASE="删除";
    static final int GREEN=Color.rgb(26,219,181),INK=Color.rgb(225,239,247),SUB=Color.rgb(127,155,177);
    private static final class Segment {PointF a,b;String type;
        Segment(PointF x,PointF y,String t){a=x;b=y;type=t;}}
    private static final class Label {PointF p;String text;
        Label(PointF p,String t){this.p=p;text=t;}}
    private final List<Segment> strokes=new ArrayList<>();
    private final List<List<PointF>> rooms=new ArrayList<>();
    private final List<Label> labels=new ArrayList<>();
    private final List<String> undoStack=new ArrayList<>(),redoStack=new ArrayList<>();
    private final List<PointF> path=new ArrayList<>();
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private String tool=WALL;private Listener listener;
    private float scale,offsetX,offsetY,lastX,lastY;
    private boolean pan;private final ScaleGestureDetector detector;
    private final float density;
    private final float snapDist=.18f; // metres, optional 0.1 m grid + axis lock
    private boolean gridSnap=true,axisSnap=true,endSnap=true;
    private float wallThickness=.18f;
    public FloorPlanView(Context c){super(c);density=getResources().getDisplayMetrics().density;scale=74*density;offsetX=34*density;offsetY=110*density;
        detector=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){float old=scale;scale=Math.max(14*density,Math.min(250*density,scale*d.getScaleFactor()));
                float f=scale/old;offsetX=d.getFocusX()-(d.getFocusX()-offsetX)*f;
                offsetY=d.getFocusY()-(d.getFocusY()-offsetY)*f;invalidate();return true;}
        });
    }
    public void setListener(Listener l){listener=l;}
    public void setTool(String tool){this.tool=tool;postMessage(tool+"工具就绪");invalidate();}
    public String getTool(){return tool;}
    public void setSnap(boolean grid,boolean axis,boolean endpoints){gridSnap=grid;axisSnap=axis;endSnap=endpoints;invalidate();}
    public void setWallThickness(float metres){wallThickness=Math.max(.05f,Math.min(1f,metres));invalidate();}
    public boolean hasContent(){return !strokes.isEmpty()||!rooms.isEmpty()||!labels.isEmpty()||!path.isEmpty();}
    public float getArea(){float area=0;for(List<PointF> r:rooms)area+=Math.abs(areaSigned(r));return area;}
    public float getWallLength(){double a=0;for(Segment s:strokes)if("wall".equals(s.type))a+=len(s.a,s.b);return (float)a;}
    private void postMessage(String s){if(listener!=null)listener.onMessage(s);}
    private void changed(){invalidate();if(listener!=null)listener.onChanged();}
    private PointF toWorld(float sx,float sy){return new PointF((sx-offsetX)/scale,(sy-offsetY)/scale);}
    private float X(float m){return offsetX+m*scale;}
    private float Y(float m){return offsetY+m*scale;}
    private float len(PointF a,PointF b){return (float)Math.hypot(a.x-b.x,a.y-b.y);}
    private PointF copy(PointF p){return new PointF(p.x,p.y);}
    private static float areaSigned(List<PointF> v){float a=0;for(int i=0;i<v.size();i++){PointF p=v.get(i),q=v.get((i+1)%v.size());a+=p.x*q.y-q.x*p.y;}return a/2f;}
    private void snapshot(){undoStack.add(toJson());if(undoStack.size()>80)undoStack.remove(0);redoStack.clear();}
    public void undo(){
        if(ROOM.equals(tool)&&!path.isEmpty()){path.remove(path.size()-1);changed();return;}
        if(!undoStack.isEmpty()){redoStack.add(toJson());loadJson(undoStack.remove(undoStack.size()-1),false);postMessage("已撤销");return;}
        if(!path.isEmpty()){path.remove(path.size()-1);changed();}
    }
    public void redo(){if(redoStack.isEmpty())return;undoStack.add(toJson());loadJson(redoStack.remove(redoStack.size()-1),false);postMessage("已重做");}
    public void clear(){snapshot();strokes.clear();rooms.clear();labels.clear();path.clear();changed();}
    public void close(){
        if(path.size()<3){postMessage("闭合至少需要 3 个点");return;}
        snapshot();List<PointF> room=new ArrayList<>();for(PointF v:path)room.add(copy(v));rooms.add(room);
        if(ROOM.equals(tool)){for(int i=0;i<path.size();i++)strokes.add(new Segment(copy(path.get(i)),copy(path.get((i+1)%path.size())),"wall"));}
        else strokes.add(new Segment(copy(path.get(path.size()-1)),copy(path.get(0)),"wall"));
        float a=Math.abs(areaSigned(room));path.clear();postMessage(String.format(Locale.CHINA,"闭合成功 · 面积 %.2f m²",a));changed();
    }
    public void addLabel(PointF at,String value){if(value==null||value.trim().isEmpty())return;snapshot();labels.add(new Label(copy(at),value.trim()));changed();}
    private PointF snapped(PointF target){PointF p=copy(target);
        if(gridSnap){p.x=Math.round(p.x*10f)/10f;p.y=Math.round(p.y*10f)/10f;}
        if(axisSnap&&!path.isEmpty()){
            PointF prev=path.get(path.size()-1);
            if(Math.abs(p.x-prev.x)<snapDist)p.x=prev.x;
            if(Math.abs(p.y-prev.y)<snapDist)p.y=prev.y;
        }
        if(endSnap){
            PointF best=null;float mind=snapDist;
            for(PointF q:path){float d=len(p,q);if(d<mind){best=q;mind=d;}}
            for(Segment s:strokes)for(PointF q:new PointF[]{s.a,s.b}){
                float d=len(p,q);if(d<mind){best=q;mind=d;}}
            if(best!=null){p=copy(best);postMessage("吸附成功 · 端点");}
        }
        return p;
    }
    private static PointF closest(PointF pt,PointF a,PointF b){
        float dx=b.x-a.x,dy=b.y-a.y,den=dx*dx+dy*dy;
        float t=den<1e-6?0:Math.max(0,Math.min(1,((pt.x-a.x)*dx+(pt.y-a.y)*dy)/den));
        return new PointF(a.x+t*dx,a.y+t*dy);
    }
    private Segment closestWall(PointF pt){Segment best=null;float md=.55f;
        for(Segment s:strokes)if("wall".equals(s.type)){
            float d=len(pt,closest(pt,s.a,s.b));if(d<md){md=d;best=s;}
        }return best;
    }
    private void tap(PointF original){
        PointF v=snapped(original);
        if(WALL.equals(tool)||ROOM.equals(tool)){
            if(path.size()>=3&&len(v,path.get(0))<snapDist){close();return;}
            if(!path.isEmpty()&&len(v,path.get(path.size()-1))<.045f){postMessage("点位太近，请选择其他位置");return;}
            if(WALL.equals(tool)&&!path.isEmpty()){snapshot();strokes.add(new Segment(copy(path.get(path.size()-1)),copy(v),"wall"));}
            path.add(v);postMessage(String.format(Locale.CHINA,"当前 %d 点 · 可点击「闭合」",path.size()));changed();return;
        }
        if(DOOR.equals(tool)||WINDOW.equals(tool)){
            Segment s=closestWall(v);if(s==null){postMessage("请在已有墙体附近点击放置"+(DOOR.equals(tool)?"门":"窗"));return;}
            PointF center=closest(v,s.a,s.b);float dx=s.b.x-s.a.x,dy=s.b.y-s.a.y;
            float d=(float)Math.hypot(dx,dy);if(d<.05f)return;
            float w=Math.min(DOOR.equals(tool)?.9f:1.2f,d*.8f);float ux=dx/d,uy=dy/d;
            PointF a=new PointF(center.x-ux*w/2,center.y-uy*w/2),b=new PointF(center.x+ux*w/2,center.y+uy*w/2);
            snapshot();strokes.add(new Segment(a,b,DOOR.equals(tool)?"door":"window"));
            postMessage(DOOR.equals(tool)?"门已沿墙放置（标注，未裁断墙体）":"窗已沿墙放置（标注，未裁断墙体）");changed();return;
        }
        if(STAIRS.equals(tool)){
            if(path.isEmpty()){path.add(v);postMessage("楼梯：选择对角点");changed();return;}
            snapshot();strokes.add(new Segment(copy(path.get(0)),copy(v),"stairs"));path.clear();changed();return;
        }
        if(COLUMN.equals(tool)){snapshot();strokes.add(new Segment(v,new PointF(v.x+.32f,v.y+.32f),"column"));changed();return;}
        if(TEXT.equals(tool)){if(listener!=null)listener.onText(v);return;}
        if(ERASE.equals(tool)){
            Segment target=null;float best=.35f;
            for(Segment s:strokes){float d=len(v,closest(v,s.a,s.b));if(d<best){best=d;target=s;}}
            if(target!=null){snapshot();strokes.remove(target);
                if("wall".equals(target.type)&&!rooms.isEmpty()){
                    rooms.clear();postMessage("墙线已删除，原闭合面积失效，请重新闭合轮廓");
                }else postMessage("已删除构件");changed();}
            else postMessage("请点选墙 / 门 / 窗 / 楼梯以删除");
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event){detector.onTouchEvent(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:lastX=event.getX();lastY=event.getY();pan=false;return true;
            case MotionEvent.ACTION_MOVE:
                if(event.getPointerCount()>=2||PAN.equals(tool)){
                    float x=event.getX(),y=event.getY();if(!detector.isInProgress()){offsetX+=x-lastX;offsetY+=y-lastY;invalidate();}
                    lastX=x;lastY=y;pan=true;
                }return true;
            case MotionEvent.ACTION_UP:
                if(!pan&&!detector.isInProgress()&&!PAN.equals(tool)&&Math.hypot(event.getX()-lastX,event.getY()-lastY)<16*density)
                    tap(toWorld(event.getX(),event.getY()));
                performClick();return true;
        }return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private void line(Canvas c,PointF a,PointF b,int color,float width){p.setColor(color);p.setStrokeWidth(width);p.setStyle(Paint.Style.STROKE);c.drawLine(X(a.x),Y(a.y),X(b.x),Y(b.y),p);p.setStyle(Paint.Style.FILL);}
    private void dot(Canvas c,PointF v,int color){p.setColor(color);c.drawCircle(X(v.x),Y(v.y),5*density,p);}
    private void print(Canvas c,String txt,float sx,float sy,int color,int size){p.setTextAlign(Paint.Align.CENTER);p.setColor(color);p.setTextSize(size*density);p.setStyle(Paint.Style.FILL);c.drawText(txt,sx,sy,p);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(Color.rgb(13,23,35));
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(density);
        int w=getWidth(),h=getHeight();float metre=Math.max(.1f,scale);
        float step=metre;if(step<30*density)step*=5;
        int col=0;for(float x=offsetX%step;x<w;x+=step){if(x>=0){p.setColor(0x33415A72);c.drawLine(x,0,x,h,p);}if(++col>200)break;}
        int row=0;for(float y=offsetY%step;y<h;y+=step){if(y>=0){p.setColor(0x33415A72);c.drawLine(0,y,w,y,p);}if(++row>200)break;}
        for(List<PointF> area:rooms){Path path=new Path();for(int i=0;i<area.size();i++){PointF pt=area.get(i);if(i==0)path.moveTo(X(pt.x),Y(pt.y));else path.lineTo(X(pt.x),Y(pt.y));}path.close();
            p.setStyle(Paint.Style.FILL);p.setColor(0x2130E0B4);c.drawPath(path,p);p.setStyle(Paint.Style.STROKE);p.setColor(GREEN);p.setStrokeWidth(1.5f*density);c.drawPath(path,p);
            float cx=0,cy=0;for(PointF pt:area){cx+=pt.x;cy+=pt.y;}cx/=area.size();cy/=area.size();print(c,String.format(Locale.US,"%.2f m²",Math.abs(areaSigned(area))),X(cx),Y(cy),GREEN,13);
        }
        for(Segment s:strokes){
            if("wall".equals(s.type)){
                line(c,s.a,s.b,0xFF25394D,Math.max(5*density,wallThickness*scale+2*density));
                line(c,s.a,s.b,INK,Math.max(3*density,wallThickness*scale));
            }else if("door".equals(s.type)){
                line(c,s.a,s.b,Color.rgb(245,177,82),3*density);
                // schematic swing arc (opening is annotation only, no wall cut)
                float angle=(float)Math.atan2(s.b.y-s.a.y,s.b.x-s.a.x);
                float r=len(s.a,s.b)*scale;
                p.setColor(0x99F5B152);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f*density);
                c.drawArc(new RectF(X(s.a.x)-r,Y(s.a.y)-r,X(s.a.x)+r,Y(s.a.y)+r),
                    (float)Math.toDegrees(angle)-90,90,false,p);p.setStyle(Paint.Style.FILL);
            }else if("window".equals(s.type)){
                line(c,s.a,s.b,Color.rgb(69,172,246),6*density);
                line(c,s.a,s.b,Color.WHITE,1.7f*density);
            }else if("stairs".equals(s.type)){
                float l=Math.min(s.a.x,s.b.x),r=Math.max(s.a.x,s.b.x),t=Math.min(s.a.y,s.b.y),b=Math.max(s.a.y,s.b.y);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*density);p.setColor(SUB);
                c.drawRect(X(l),Y(t),X(r),Y(b),p);
                for(int i=1;i<8;i++){float yy=t+(b-t)*i/8f;c.drawLine(X(l),Y(yy),X(r),Y(yy),p);}p.setStyle(Paint.Style.FILL);
            }else if("column".equals(s.type)){
                p.setStyle(Paint.Style.STROKE);p.setColor(0xFFBBBFCA);p.setStrokeWidth(3*density);
                c.drawRect(X(s.a.x),Y(s.a.y),X(s.b.x),Y(s.b.y),p);p.setStyle(Paint.Style.FILL);
            }
        }
        for(Label t:labels)print(c,t.text,X(t.p.x),Y(t.p.y),Color.WHITE,13);
        for(int i=0;i<path.size();i++){PointF pt=path.get(i);dot(c,pt,GREEN);if(i>0)line(c,path.get(i-1),pt,GREEN,2*density);}
        if(path.size()>0)print(c,"未闭合 · "+path.size()+"点",w*.5f,34*density,GREEN,12);
        p.setTextAlign(Paint.Align.LEFT);p.setTextSize(12*density);p.setColor(SUB);
        c.drawText(String.format(Locale.US,"1m = %.0f px | %s | %.2f m²",scale,tool,getArea()),14*density,h-18*density,p);
    }
    private static JSONArray pJson(PointF v) throws Exception{return new JSONArray().put((double)v.x).put((double)v.y);}
    private static PointF pFrom(JSONArray a){return new PointF((float)a.optDouble(0),(float)a.optDouble(1));}
    public String toJson(){try{
        JSONObject o=new JSONObject();o.put("v",1);o.put("scale",scale/density);o.put("ox",offsetX/density);o.put("oy",offsetY/density);o.put("thick",wallThickness);
        JSONArray ss=new JSONArray();for(Segment s:strokes)ss.put(new JSONObject().put("a",pJson(s.a)).put("b",pJson(s.b)).put("type",s.type));o.put("strokes",ss);
        JSONArray rr=new JSONArray();for(List<PointF> r:rooms){JSONArray pp=new JSONArray();for(PointF v:r)pp.put(pJson(v));rr.put(pp);}o.put("rooms",rr);
        JSONArray ll=new JSONArray();for(Label t:labels)ll.put(new JSONObject().put("p",pJson(t.p)).put("text",t.text));o.put("labels",ll);
        JSONArray pathArray=new JSONArray();for(PointF v:path)pathArray.put(pJson(v));o.put("pending",pathArray);
        return o.toString();
    }catch(Exception e){return "{}";}}
    public void loadJson(String str){loadJson(str,true);}
    private void loadJson(String str,boolean resetUndo){try{
        JSONObject o=new JSONObject(str);strokes.clear();rooms.clear();labels.clear();path.clear();
        JSONArray ss=o.optJSONArray("strokes");if(ss!=null)for(int i=0;i<ss.length();i++){
            JSONObject s=ss.getJSONObject(i);strokes.add(new Segment(pFrom(s.getJSONArray("a")),pFrom(s.getJSONArray("b")),s.optString("type","wall")));}
        JSONArray rr=o.optJSONArray("rooms");if(rr!=null)for(int i=0;i<rr.length();i++){
            JSONArray a=rr.getJSONArray(i);List<PointF> region=new ArrayList<>();for(int j=0;j<a.length();j++)region.add(pFrom(a.getJSONArray(j)));if(region.size()>2)rooms.add(region);}
        JSONArray ll=o.optJSONArray("labels");if(ll!=null)for(int i=0;i<ll.length();i++){
            JSONObject a=ll.getJSONObject(i);labels.add(new Label(pFrom(a.getJSONArray("p")),a.optString("text","")));}
        JSONArray pa=o.optJSONArray("pending");if(pa!=null)for(int i=0;i<pa.length();i++)path.add(pFrom(pa.getJSONArray(i)));
        scale=(float)o.optDouble("scale",74)*density;offsetX=(float)o.optDouble("ox",34)*density;offsetY=(float)o.optDouble("oy",110)*density;
        wallThickness=(float)o.optDouble("thick",.18);
        if(resetUndo){undoStack.clear();redoStack.clear();}
        changed();
    }catch(Exception ex){postMessage("打开平面图失败：格式无效");}}
    public Bitmap capture(){if(getWidth()==0||getHeight()==0)return null;
        Bitmap b=Bitmap.createBitmap(getWidth(),getHeight(),Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(b);draw(c);return b;}
    private static void pair(StringBuilder b,int code,Object v){b.append(code).append('\n').append(v).append('\n');}
    private static void dline(StringBuilder b,String layer,PointF a,PointF end){
        pair(b,0,"LINE");pair(b,8,layer);pair(b,10,a.x);pair(b,20,-a.y);pair(b,30,0);
        pair(b,11,end.x);pair(b,21,-end.y);pair(b,31,0);
    }
    public String exportDxf(){
        StringBuilder b=new StringBuilder(8192);
        pair(b,0,"SECTION");pair(b,2,"HEADER");pair(b,9,"$ACADVER");pair(b,1,"AC1009");pair(b,9,"$INSUNITS");pair(b,70,6);
        pair(b,0,"ENDSEC");pair(b,0,"SECTION");pair(b,2,"ENTITIES");
        for(Segment s:strokes){String layer=s.type.toUpperCase(Locale.US);
            if("stairs".equals(s.type)||"column".equals(s.type)){
                PointF a=s.a,z=s.b;PointF c=new PointF(a.x,z.y),d=new PointF(z.x,a.y);
                dline(b,layer,a,c);dline(b,layer,c,z);dline(b,layer,z,d);dline(b,layer,d,a);
                if("stairs".equals(s.type))for(int i=1;i<8;i++){
                    float y=a.y+(z.y-a.y)*i/8f;dline(b,layer,new PointF(a.x,y),new PointF(z.x,y));
                }
            }else dline(b,layer,s.a,s.b);
        }
        for(Label t:labels){pair(b,0,"TEXT");pair(b,8,"LABEL");pair(b,10,t.p.x);pair(b,20,-t.p.y);pair(b,30,0);pair(b,40,.2);
            // DXF R12 ASCII cannot represent Chinese reliably; use placeholder and keep Chinese in PNG / JSON.
            String ascii=t.text.replaceAll("[^\\x20-\\x7E]","?");pair(b,1,ascii);}
        pair(b,0,"ENDSEC");pair(b,0,"EOF");return b.toString();
    }
}