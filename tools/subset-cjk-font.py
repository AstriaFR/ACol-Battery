"""生成主页中文圆润标题的字形子集并更改内部名称。"""
from pathlib import Path
import argparse
from fontTools import subset
from fontTools.ttLib import TTFont

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('source',type=Path,help='源泉圆体原始粗体文件')
args=parser.parse_args()
assets=Path(__file__).resolve().parents[1]/'src/main/assets/webroot/assets'
if not (assets/'acol-cjk-OFL.txt').is_file():raise SystemExit('缺少源泉圆体原版权及 OFL 文件。')
font=TTFont(args.source)
copyrights=[entry.toUnicode() for entry in font['name'].names if entry.nameID==0]
if 'CFF ' in font:
    source_top=font['CFF '].cff.topDictIndex[0]
    for field in ['Copyright','Notice']:
        if hasattr(source_top,field):copyrights.append(str(getattr(source_top,field)))
copyrights.append((assets/'acol-cjk-OFL.txt').read_text(encoding='utf-8').split('This Font Software')[0].strip())
if not set(map(ord,'电池')).issubset(font.getBestCmap()):raise SystemExit('字体缺少简体“电池”字形。')
options=subset.Options();options.recalc_timestamp=False
options.name_IDs=['*'];options.name_languages=['*'];options.name_legacy=True
options.layout_features=[]
subsetter=subset.Subsetter(options=options);subsetter.populate(text='电池');subsetter.subset(font)
family='ACol CJK Rounded';full=family+' Bold';postscript='AColCJKRounded-Bold'
identity_ids={1,2,3,4,5,6,16,17,18,20,21,22,25}
font['name'].names=[entry for entry in font['name'].names if entry.nameID not in identity_ids]
values={0:'\n'.join(dict.fromkeys(text for text in copyrights if text.strip())),1:family,2:'Bold',3:'AstriaFR:AColCJKRounded-Bold:1.0',4:full,5:'Version 1.000; ACol CJK subset',6:postscript,
        16:family,17:'Bold',18:full,21:family,22:'Bold',25:'AColCJKRounded',
        10:'Chinese title subset derived from GenSen Rounded. Original glyph design and copyright remain with the original authors. Subsetting and internal naming: AstriaFR.',
        13:'This Font Software is licensed under the SIL Open Font License, Version 1.1. Original copyright and full license: acol-cjk-OFL.txt.',
        14:'https://openfontlicense.org/open-font-license-official-text/'}
for name_id,value in values.items():
    font['name'].setName(value,name_id,3,1,0x409);font['name'].setName(value,name_id,1,0,0)
font['head'].fontRevision=1.0
if 'CFF ' in font:
    font['CFF '].cff.fontNames=[postscript]
    top=font['CFF '].cff.topDictIndex[0];top.FamilyName=family;top.FullName=full
    if hasattr(top,'FDArray'):
        for i,dictionary in enumerate(top.FDArray):dictionary.FontName=postscript+'-FD'+str(i)
font.save(assets/'acol-cjk.otf')
print('已生成主页中文标题字体。')
