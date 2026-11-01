"""Build the APK using configured tools and the version already in the manifest."""
from pathlib import Path
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from font_checks import validate_home_fonts

app=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java-home',default=os.environ.get('JAVA_HOME'),help='JDK root; defaults to JAVA_HOME or javac on PATH')
parser.add_argument('--sdk',default=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME'),help='Android SDK root')
parser.add_argument('--ndk',default=os.environ.get('ANDROID_NDK_HOME') or os.environ.get('ANDROID_NDK_ROOT'),help='NDK root; default SDK/ndk/29.0.14206865')
parser.add_argument('--platform',default='android-37',help='SDK platform directory; default android-37 (also accepts installed android-37.0)')
parser.add_argument('--build-tools',default='37.0.0',help='SDK Build Tools version')
parser.add_argument('--keystore',default=os.environ.get('ACO_KEYSTORE'),help='Signing keystore path')
parser.add_argument('--password-file',default=os.environ.get('ACO_KEYSTORE_PASSWORD_FILE'),help='UTF-8 file containing the keystore password')
parser.add_argument('--key-alias',default=os.environ.get('ACO_KEY_ALIAS','aco'),help='Signing key alias')
parser.add_argument('--check',action='store_true',help='Check configuration without building or writing project files')
args=parser.parse_args()
exe='.exe' if os.name=='nt' else ''
if args.java_home:
    jdk=Path(args.java_home).expanduser().resolve()/'bin'
else:
    javac=shutil.which('javac')
    if not javac:parser.error('Set JAVA_HOME or --java-home to a JDK 17 installation.')
    jdk=Path(javac).resolve().parent
if not args.sdk:parser.error('Set ANDROID_SDK_ROOT, ANDROID_HOME or --sdk.')
sdk=Path(args.sdk).expanduser().resolve()
ndk_root=Path(args.ndk).expanduser().resolve() if args.ndk else sdk/'ndk/29.0.14206865'
host='windows-x86_64' if os.name=='nt' else 'darwin-x86_64' if sys.platform=='darwin' else 'linux-x86_64'
ndk=ndk_root/'toolchains/llvm/prebuilt'/host
tools=sdk/'build-tools'/args.build_tools
android=sdk/'platforms'/args.platform/'android.jar'
if args.platform=='android-37' and not android.is_file():android=sdk/'platforms/android-37.0/android.jar'
if not args.keystore or not args.password_file:parser.error('Provide --keystore and --password-file, or ACO_KEYSTORE and ACO_KEYSTORE_PASSWORD_FILE.')
key=Path(args.keystore).expanduser().resolve()
password=Path(args.password_file).expanduser().resolve()
key_alias=args.key_alias
required=[jdk/('javac'+exe),jdk/('java'+exe),android,tools/('aapt2'+exe),tools/('zipalign'+exe),
          tools/'lib/d8.jar',tools/'lib/apksigner.jar',ndk/('bin/clang'+exe),key,password]
missing=[str(path) for path in required if not path.is_file()]
if missing:parser.error('Required files not found:\n'+'\n'.join(missing))
try:
    manifest=ET.parse(app/'src/main/AndroidManifest.xml').getroot()
    version=manifest.get('{http://schemas.android.com/apk/res/android}versionName','')
    if not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z._-]*',version):raise ValueError('Invalid manifest versionName')
    signing_password=password.read_text(encoding='utf-8-sig').strip()
    if not signing_password:raise ValueError('Signing password file is empty')
    validate_home_fonts(app/'src/main/assets/webroot/assets')
except (OSError,ValueError,ET.ParseError) as error:parser.error(str(error))
if args.check:
    print('Configuration check passed; manifest version: '+version)
    raise SystemExit(0)
build=app/'build'
for name in ['daemon-classes','daemon-dex','classes','dex','generated','res','payload']:
    target=build/name
    target.resolve().relative_to(build.resolve())
    if target.is_symlink():raise SystemExit(f'Build output must not be a symbolic link: {target}')
    if target.exists():shutil.rmtree(target)
for name in ['tmp','daemon-classes','daemon-dex','classes','dex','generated','res/drawable','payload/bin']:(build/name).mkdir(parents=True,exist_ok=True)
(app/'dist').mkdir(exist_ok=True)
env=os.environ.copy();env.update(TEMP=str(build/'tmp'),TMP=str(build/'tmp'),TMPDIR=str(build/'tmp'),ACO_SIGNING_PASSWORD=signing_password)
def run(*args):subprocess.run([str(arg) for arg in args],check=True,env=env)
def jar_classes(directory,output):
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(directory.rglob('*.class')):archive.write(path,path.relative_to(directory).as_posix())

run(jdk/('javac'+exe),'--release','8','-encoding','UTF-8','-cp',android,'-d',build/'daemon-classes',*sorted((app/'daemon/src').rglob('*.java')),*sorted((app/'common/src').rglob('*.java')))
jar_classes(build/'daemon-classes',build/'daemon.jar')
run(jdk/('java'+exe),'-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','35','--lib',android,'--output',build/'daemon-dex',build/'daemon.jar')
with zipfile.ZipFile(build/'payload/bin/daemon.dex.jar','w',zipfile.ZIP_DEFLATED) as archive:archive.write(build/'daemon-dex/classes.dex','classes.dex')
for source,output,flags in [('wait.c','libcurvewait.so',['-shared','-fPIC']),('launcher.c','aco-launcher',['-fPIE','-pie'])]:
    run(ndk/('bin/clang'+exe),'--target=aarch64-linux-android35','--sysroot='+str(ndk/'sysroot'),*flags,'-O2','-Wall','-Wextra','-Werror','-Wl,-z,max-page-size=16384','-Wl,-z,relro,-z,now',app/'daemon/src'/source,'-o',build/'payload/bin'/output)
for path in (app/'runtime').glob('*.sh'):
    # Android sh treats CR in CRLF as part of options/commands. Normalize bytes
    # here as well as in source control, including Windows-edited scripts.
    script=path.read_bytes().replace(b'\r\n',b'\n').replace(b'\r',b'\n')
    if script.startswith(b'\xef\xbb\xbf'):script=script[3:]
    (build/'payload'/path.name).write_bytes(script)
payload=app/'src/main/assets/payload.zip'
with zipfile.ZipFile(payload,'w',zipfile.ZIP_DEFLATED) as archive:
    for path in sorted((build/'payload').rglob('*')):
        if path.is_file():archive.write(path,path.relative_to(build/'payload').as_posix())
(app/'src/main/assets/payload.sha256').write_text(hashlib.sha256(payload.read_bytes()).hexdigest(),encoding='ascii')
shutil.copyfile(app/'src/main/assets/webroot/assets/acol-icon.png',build/'res/drawable/acol_icon.png')
compiled=build/'res/compiled'
compiled.mkdir(exist_ok=True)
resource_inputs=[build/'res/drawable/acol_icon.png',*sorted((app/'src/main/res').rglob('*.xml'))]
for resource in resource_inputs:run(tools/('aapt2'+exe),'compile','--no-crunch',resource,'-o',compiled)
resource_args=[]
for resource in sorted(compiled.glob('*.flat')):resource_args.extend(['-R',resource])
run(tools/('aapt2'+exe),'link','--manifest',app/'src/main/AndroidManifest.xml','-I',android,*resource_args,'--java',build/'generated','-o',build/'resources.apk')
run(jdk/('javac'+exe),'--release','8','-encoding','UTF-8','-cp',android,'-d',build/'classes',*sorted((app/'src/main/java').rglob('*.java')),*sorted((app/'common/src').rglob('*.java')))
jar_classes(build/'classes',build/'app.jar')
run(jdk/('java'+exe),'-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','35','--lib',android,'--output',build/'dex',build/'app.jar')
shutil.copyfile(build/'resources.apk',build/'unsigned.apk')
with zipfile.ZipFile(build/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as archive:
    archive.write(build/'dex/classes.dex','classes.dex')
    for path in sorted((app/'src/main/assets').rglob('*')):
        if path.is_file():archive.write(path,'assets/'+path.relative_to(app/'src/main/assets').as_posix())
run(tools/('zipalign'+exe),'-f','-p','4',build/'unsigned.apk',build/'aligned.apk')
output=app/f'dist/ACol-Battery-v{version}.apk'
run(jdk/('java'+exe),'-jar',tools/'lib/apksigner.jar','sign','--v4-signing-enabled','false','--ks',key,'--ks-key-alias',key_alias,'--ks-pass','env:ACO_SIGNING_PASSWORD','--out',output,build/'aligned.apk')
output.with_suffix('.apk.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest()+'  '+output.name+'\n',encoding='ascii')
print(f'APK: {output}\nSize: {output.stat().st_size} bytes')
