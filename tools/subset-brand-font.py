"""生成主页圆润字标字体，保留默认带弯脚的小写 l。"""
from pathlib import Path
import argparse
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('source',type=Path,help='Nunito 原始字体文件')
args=parser.parse_args()
assets=Path(__file__).resolve().parents[1]/'src/main/assets/webroot/assets'
if not (assets/'acol-brand-OFL.txt').is_file():raise SystemExit('缺少 Nunito 的版权与 OFL 许可文件。')
font=TTFont(args.source)
if 'fvar' in font:font=instantiateVariableFont(font,{'wght':700},inplace=True)
options=subset.Options();options.recalc_timestamp=False
options.name_IDs=['*'];options.name_languages=['*'];options.name_legacy=True
options.layout_features=['kern']
subsetter=subset.Subsetter(options=options);subsetter.populate(text='ACol ');subsetter.subset(font)
family='ACol Brand Rounded';full=family+' Bold';postscript='AColBrandRounded-Bold'
identity_ids={1,2,3,4,5,6,16,17,18,20,21,22,25}
font['name'].names=[entry for entry in font['name'].names if entry.nameID not in identity_ids]
values={1:family,2:'Bold',3:'AstriaFR:AColBrandRounded-Bold:1.0',4:full,5:'Version 1.000; ACol brand subset',6:postscript,
        16:family,17:'Bold',18:full,21:family,22:'Bold',25:'AColBrandRounded',
        10:'Static bold glyph subset for the ACol home title. Original design and copyright: The Nunito Project Authors. Subsetting and internal naming: AstriaFR.',
        13:'This Font Software is licensed under the SIL Open Font License, Version 1.1. Original copyright and full license: acol-brand-OFL.txt.',
        14:'https://openfontlicense.org/open-font-license-official-text/'}
for name_id,value in values.items():
    font['name'].setName(value,name_id,3,1,0x409);font['name'].setName(value,name_id,1,0,0)
font['head'].fontRevision=1.0
font.save(assets/'acol-brand.ttf')
print('已生成主页字标字体。')
