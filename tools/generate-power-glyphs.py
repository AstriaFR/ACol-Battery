from fontTools.ttLib import TTFont
from fontTools.pens.basePen import BasePen
from pathlib import Path
font=TTFont('ACol-Battery/src/main/assets/webroot/assets/acol-brand.ttf')
glyphs=font.getGlyphSet(); cmap=font.getBestCmap(); upem=font['head'].unitsPerEm
class Pen(BasePen):
 def __init__(self): super().__init__(glyphs); self.ops=[]
 def _moveTo(self,p): self.ops.append(('moveTo',p))
 def _lineTo(self,p): self.ops.append(('lineTo',p))
 def _curveToOne(self,p1,p2,p3): self.ops.append(('cubicTo',p1+p2+p3))
 def _qCurveToOne(self,p1,p2): self.ops.append(('quadTo',p1+p2))
 def _closePath(self): self.ops.append(('close',()))
 def _endPath(self): pass
lines=['package cn.astriafr.acobattery.daemon;', '', 'import android.graphics.Canvas;', 'import android.graphics.Paint;', 'import android.graphics.Path;', '', '/** Nunito bold outlines from the OFL-licensed ACol brand font. */', 'final class PowerGlyphs {', '    static final float EM='+str(upem)+'f;', '    private static final Path[] PATHS=new Path[12];', '    private static final float[] ADVANCES=new float[12];', '    static {']
chars='0123456789.W'
for i,ch in enumerate(chars):
 name=cmap[ord(ch)]; pen=Pen(); glyphs[name].draw(pen); lines += [f'        Path p{i}=new Path();']
 for method,pts in pen.ops:
  args=','.join(str(round(v,3))+'f' for v in pts)
  lines.append(f'        p{i}.{method}({args});')
 lines.append(f'        PATHS[{i}]=p{i}; ADVANCES[{i}]={font["hmtx"][name][0]}f;')
lines += ['    }','    private static int index(char c){return c>=\'0\'&&c<=\'9\'?c-\'0\':c==\'.\'?10:c==\'W\'?11:-1;}', '    static float width(String text){float result=0;for(int i=0;i<text.length();i++){int n=index(text.charAt(i));result+=n<0?EM*.5f:ADVANCES[n];}return result;}', '    static void draw(Canvas canvas,Paint paint,String text){for(int i=0;i<text.length();i++){int n=index(text.charAt(i));if(n<0){canvas.drawLine(EM*.08f,EM*.35f,EM*.42f,EM*.35f,paint);canvas.translate(EM*.5f,0);}else{canvas.drawPath(PATHS[n],paint);canvas.translate(ADVANCES[n],0);}}}', '}']
Path('ACol-Battery/daemon/src/cn/astriafr/acobattery/daemon/PowerGlyphs.java').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('glyphs',chars,'upem',upem,'ascent/descent',font['hhea'].ascent,font['hhea'].descent)
