"""使用环境变量配置的 Android 工具链构建 ACol 电池。"""
from pathlib import Path
import hashlib
import os
import secrets
import shutil
import subprocess
import zipfile
import sys
from font_checks import validate_home_fonts

app=Path(__file__).resolve().parents[1]
validate_home_fonts(app/'src/main/assets/webroot/assets')
def configured_path(*names):
    for name in names:
        if os.environ.get(name):return Path(os.environ[name]).expanduser().resolve()
    raise SystemExit('请设置环境变量：'+' 或 '.join(names))
def newest(directory):
    candidates=[p for p in directory.iterdir() if p.is_dir()] if directory.is_dir() else []
    if not candidates:raise SystemExit('缺少工具目录：'+str(directory))
    import re
    return max(candidates,key=lambda p:tuple(int(x) for x in re.findall(r'\d+',p.name)))
sdk=configured_path('ANDROID_HOME','ANDROID_SDK_ROOT')
jdk_home=Path(os.environ['JAVA_HOME']).expanduser().resolve() if os.environ.get('JAVA_HOME') else Path(shutil.which('javac') or sys.exit('请安装 JDK 并设置 JAVA_HOME。')).resolve().parent.parent
jdk=jdk_home/'bin'
platform=Path(os.environ['ACO_ANDROID_PLATFORM']) if os.environ.get('ACO_ANDROID_PLATFORM') else newest(sdk/'platforms')
android=platform/'android.jar'
tools=Path(os.environ['ACO_BUILD_TOOLS']) if os.environ.get('ACO_BUILD_TOOLS') else newest(sdk/'build-tools')
ndk_home=Path(os.environ['ANDROID_NDK_HOME']) if os.environ.get('ANDROID_NDK_HOME') else newest(sdk/'ndk')
host={'win32':'windows-x86_64','linux':'linux-x86_64','darwin':'darwin-x86_64'}.get(sys.platform)
if host is None:raise SystemExit('不支持的构建系统：'+sys.platform)
ndk=ndk_home/'toolchains/llvm/prebuilt'/host
def binary(directory,name):return directory/(name+'.exe' if sys.platform=='win32' else name)
for path in [android,tools/'lib/d8.jar',tools/'lib/apksigner.jar',binary(jdk,'javac'),binary(ndk/'bin','clang')]:
    if not path.is_file():raise SystemExit('未找到构建工具：'+str(path))
build=app/'build'
for name in ['tmp','daemon-classes','daemon-dex','classes','dex','generated','res/drawable','payload/bin','signing']:(build/name).mkdir(parents=True,exist_ok=True)
(app/'dist').mkdir(exist_ok=True)
env=os.environ.copy();env.update(TEMP=str(build/'tmp'),TMP=str(build/'tmp'),TMPDIR=str(build/'tmp'))
def run(*args):subprocess.run([str(arg) for arg in args],check=True,env=env)
def jar_classes(directory,output):
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(directory.rglob('*.class')):archive.write(path,path.relative_to(directory).as_posix())

run(binary(jdk,'javac'),'--release','8','-encoding','UTF-8','-cp',android,'-d',build/'daemon-classes',*sorted((app/'daemon/src').rglob('*.java')),*sorted((app/'common/src').rglob('*.java')))
jar_classes(build/'daemon-classes',build/'daemon.jar')
run(binary(jdk,'java'),'-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','35','--lib',android,'--output',build/'daemon-dex',build/'daemon.jar')
with zipfile.ZipFile(build/'payload/bin/daemon.dex.jar','w',zipfile.ZIP_DEFLATED) as archive:archive.write(build/'daemon-dex/classes.dex','classes.dex')
for source,output,flags in [('wait.c','libcurvewait.so',['-shared','-fPIC']),('launcher.c','aco-launcher',['-fPIE','-pie'])]:
    run(binary(ndk/'bin','clang'),'--target=aarch64-linux-android35','--sysroot='+str(ndk/'sysroot'),*flags,'-O2','-Wall','-Wextra','-Werror','-Wl,-z,max-page-size=16384','-Wl,-z,relro,-z,now',app/'daemon/src'/source,'-o',build/'payload/bin'/output)
for path in (app/'runtime').glob('*.sh'):shutil.copyfile(path,build/'payload'/path.name)
payload=app/'src/main/assets/payload.zip'
with zipfile.ZipFile(payload,'w',zipfile.ZIP_DEFLATED) as archive:
    for path in sorted((build/'payload').rglob('*')):
        if path.is_file():archive.write(path,path.relative_to(build/'payload').as_posix())
(app/'src/main/assets/payload.sha256').write_text(hashlib.sha256(payload.read_bytes()).hexdigest(),encoding='ascii')
shutil.copyfile(app/'src/main/assets/webroot/assets/acol-icon.png',build/'res/drawable/acol_icon.png')
run(binary(tools,'aapt2'),'compile','--no-crunch',build/'res/drawable/acol_icon.png','-o',build)
run(binary(tools,'aapt2'),'link','--manifest',app/'src/main/AndroidManifest.xml','-I',android,'-R',build/'drawable_acol_icon.png.flat','--java',build/'generated','-o',build/'resources.apk')
run(binary(jdk,'javac'),'--release','8','-encoding','UTF-8','-cp',android,'-d',build/'classes',*sorted((app/'src/main/java').rglob('*.java')),*sorted((app/'common/src').rglob('*.java')))
jar_classes(build/'classes',build/'app.jar')
run(binary(jdk,'java'),'-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','35','--lib',android,'--output',build/'dex',build/'app.jar')
shutil.copyfile(build/'resources.apk',build/'unsigned.apk')
with zipfile.ZipFile(build/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as archive:
    archive.write(build/'dex/classes.dex','classes.dex')
    for name in ['LICENSE','NOTICE','THIRD_PARTY_NOTICES.md']:
        archive.write(app/name,'assets/licenses/'+name)
    for path in sorted((app/'licenses').glob('*')):
        if path.is_file():archive.write(path,'assets/licenses/'+path.name)
    for path in sorted((app/'src/main/assets').rglob('*')):
        if path.is_file():archive.write(path,'assets/'+path.relative_to(app/'src/main/assets').as_posix())
run(binary(tools,'zipalign'),'-f','-p','4',build/'unsigned.apk',build/'aligned.apk')
external_key=bool(os.environ.get('ACO_KEYSTORE'))
key=Path(os.environ['ACO_KEYSTORE']).expanduser().resolve() if external_key else build/'signing/aco.p12'
password=configured_path('ACO_KEYSTORE_PASSWORD_FILE') if external_key else build/'signing/password.txt'
alias=os.environ.get('ACO_KEY_ALIAS','aco')
if external_key and (not key.is_file() or not password.is_file()):raise SystemExit('签名密钥或密码文件不存在。')
if not password.exists():password.write_text(secrets.token_urlsafe(32),encoding='ascii')
env['ACO_SIGNING_PASSWORD']=password.read_text(encoding='ascii').strip()
if not key.exists():run(binary(jdk,'keytool'),'-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass:env','ACO_SIGNING_PASSWORD','-keypass:env','ACO_SIGNING_PASSWORD','-alias',alias,'-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=ACol Battery, O=AstriaFR, C=CN')
output=app/'dist/ACol-Battery.apk'
run(binary(jdk,'java'),'-jar',tools/'lib/apksigner.jar','sign','--v4-signing-enabled','false','--ks',key,'--ks-key-alias',alias,'--ks-pass','env:ACO_SIGNING_PASSWORD','--out',output,build/'aligned.apk')
output.with_suffix('.apk.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest()+'  '+output.name+'\n',encoding='ascii')
print(f'APK: {output}\nSize: {output.stat().st_size} bytes')
