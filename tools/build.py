"""Build the standalone APK; r versions increment only for successful test builds."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import zipfile
from font_checks import validate_home_fonts
from test_version import prepare_test_version, complete_test_version

app=Path(__file__).resolve().parents[1]
version,version_state_path,version_state=prepare_test_version(app)
validate_home_fonts(app/'src/main/assets/webroot/assets')
workspace=app.parent
toolchain=workspace/'.android-toolchain-locationspoofer'
jdk=toolchain/'jdk/jdk-17.0.20.1+1/bin'
sdk=toolchain/'sdk'
android=sdk/'platforms/android-37.0/android.jar'
tools=sdk/'build-tools/37.0.0'
ndk=sdk/'ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64'
build=app/'build'
for name in ['daemon-classes','daemon-dex','classes','dex','generated','res','payload']:
    target=build/name
    target.resolve().relative_to(build.resolve())
    if target.is_symlink():raise SystemExit(f'Build output must not be a symbolic link: {target}')
    if target.exists():shutil.rmtree(target)
for name in ['tmp','daemon-classes','daemon-dex','classes','dex','generated','res/drawable','payload/bin']:(build/name).mkdir(parents=True,exist_ok=True)
(app/'dist').mkdir(exist_ok=True)
env=os.environ.copy();env.update(TEMP=str(build/'tmp'),TMP=str(build/'tmp'),TMPDIR=str(build/'tmp'))
def run(*args):subprocess.run([str(arg) for arg in args],check=True,env=env)
def jar_classes(directory,output):
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(directory.rglob('*.class')):archive.write(path,path.relative_to(directory).as_posix())

run(jdk/'javac.exe','--release','8','-encoding','UTF-8','-cp',android,'-d',build/'daemon-classes',*sorted((app/'daemon/src').rglob('*.java')),*sorted((app/'common/src').rglob('*.java')))
jar_classes(build/'daemon-classes',build/'daemon.jar')
run(jdk/'java.exe','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','35','--lib',android,'--output',build/'daemon-dex',build/'daemon.jar')
with zipfile.ZipFile(build/'payload/bin/daemon.dex.jar','w',zipfile.ZIP_DEFLATED) as archive:archive.write(build/'daemon-dex/classes.dex','classes.dex')
for source,output,flags in [('wait.c','libcurvewait.so',['-shared','-fPIC']),('launcher.c','aco-launcher',['-fPIE','-pie'])]:
    run(ndk/'bin/clang.exe','--target=aarch64-linux-android35','--sysroot='+str(ndk/'sysroot'),*flags,'-O2','-Wall','-Wextra','-Werror','-Wl,-z,max-page-size=16384','-Wl,-z,relro,-z,now',app/'daemon/src'/source,'-o',build/'payload/bin'/output)
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
for resource in resource_inputs:run(tools/'aapt2.exe','compile','--no-crunch',resource,'-o',compiled)
resource_args=[]
for resource in sorted(compiled.glob('*.flat')):resource_args.extend(['-R',resource])
run(tools/'aapt2.exe','link','--manifest',app/'src/main/AndroidManifest.xml','-I',android,*resource_args,'--java',build/'generated','-o',build/'resources.apk')
run(jdk/'javac.exe','--release','8','-encoding','UTF-8','-cp',android,'-d',build/'classes',*sorted((app/'src/main/java').rglob('*.java')),*sorted((app/'common/src').rglob('*.java')))
jar_classes(build/'classes',build/'app.jar')
run(jdk/'java.exe','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','35','--lib',android,'--output',build/'dex',build/'app.jar')
shutil.copyfile(build/'resources.apk',build/'unsigned.apk')
with zipfile.ZipFile(build/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as archive:
    archive.write(build/'dex/classes.dex','classes.dex')
    for path in sorted((app/'src/main/assets').rglob('*')):
        if path.is_file():archive.write(path,'assets/'+path.relative_to(app/'src/main/assets').as_posix())
run(tools/'zipalign.exe','-f','-p','4',build/'unsigned.apk',build/'aligned.apk')
key=Path(os.environ.get('ACO_KEYSTORE',app/'signing/aco.p12')).expanduser()
password=Path(os.environ.get('ACO_KEYSTORE_PASSWORD_FILE',app/'signing/password.txt')).expanduser()
key_alias=os.environ.get('ACO_KEY_ALIAS','aco')
if not key.is_file():raise SystemExit(f'Signing keystore not found: {key}')
if not password.is_file():raise SystemExit(f'Signing password file not found: {password}')
env['ACO_SIGNING_PASSWORD']=password.read_text(encoding='ascii').strip()
if not env['ACO_SIGNING_PASSWORD']:raise SystemExit(f'Signing password file is empty: {password}')
output=app/f'dist/ACol-Battery-v{version}.apk'
run(jdk/'java.exe','-jar',tools/'lib/apksigner.jar','sign','--v4-signing-enabled','false','--ks',key,'--ks-key-alias',key_alias,'--ks-pass','env:ACO_SIGNING_PASSWORD','--out',output,build/'aligned.apk')
output.with_suffix('.apk.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest()+'  '+output.name+'\n',encoding='ascii')
complete_test_version(version_state_path,version_state)
print(f'APK: {output}\nSize: {output.stat().st_size} bytes')
