"""用标准库核对随应用分发的标题字体名称及许可。"""
import struct

def font_names(path):
    data=path.read_bytes()
    count=struct.unpack_from('>H',data,4)[0]
    tables={}
    for i in range(count):
        tag,checksum,offset,length=struct.unpack_from('>4sIII',data,12+i*16)
        tables[tag]=(offset,length)
    offset,length=tables[b'name']
    if offset+length>len(data):raise ValueError('字体名称表损坏。')
    _,count,strings=struct.unpack_from('>HHH',data,offset)
    names={}
    for i in range(count):
        platform,encoding,language,name_id,size,start=struct.unpack_from('>HHHHHH',data,offset+6+i*12)
        start=offset+strings+start
        if start+size>offset+length:raise ValueError('字体名称字符串超出边界。')
        value=data[start:start+size].decode('utf-16-be' if platform in (0,3) else 'mac_roman')
        names.setdefault(name_id,[]).append(value)
    return names

def validate_home_fonts(assets):
    for stem,extension,family,postscript,author in [
        ('acol-brand','ttf','ACol Brand Rounded','AColBrandRounded-Bold','Nunito'),
        ('acol-cjk','otf','ACol CJK Rounded','AColCJKRounded-Bold','Adobe')]:
        names=font_names(assets/(stem+'.'+extension))
        for name_id,expected in {1:family,4:family+' Bold',6:postscript,16:family}.items():
            if not names.get(name_id) or any(value!=expected for value in names[name_id]):
                raise ValueError('主页字体内部名称不正确：'+stem)
        if not any(value.strip() for value in names.get(0,[])):raise ValueError('主页字体缺少原版权信息：'+stem)
        if not any('SIL Open Font License' in value for value in names.get(13,[])):raise ValueError('主页字体缺少 OFL 许可标记：'+stem)
        license_text=(assets/(stem+'-OFL.txt')).read_text(encoding='utf-8')
        if 'SIL OPEN FONT LICENSE' not in license_text or author not in license_text:raise ValueError('主页字体原版权及 OFL 文件不完整：'+stem)
