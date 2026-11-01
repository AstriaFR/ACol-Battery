# 构建与资源工具

发布源码时将本目录作为项目的 `tools/` 目录使用。本地开发用的另一份工具独立维护。

## APK 构建

需要 Python 3、JDK 17、Android SDK 和 NDK。默认使用 Android 37 平台、Build Tools 37.0.0、NDK 29.0.14206865，可通过参数指定其他已安装版本。构建目标为 Android 15 及以上的 ARM64 设备。

构建脚本使用 Python 标准库及本目录的 `font_checks.py`，无需安装字体处理依赖。脚本读取清单中已有的版本号，不自动递增编号，也不改写源码中的版本信息。

| 配置 | 环境变量 | 命令参数 |
| --- | --- | --- |
| JDK 根目录 | `JAVA_HOME`，未设置时尝试 PATH 中的 javac | `--java-home` |
| SDK 根目录 | `ANDROID_SDK_ROOT` 或 `ANDROID_HOME` | `--sdk` |
| NDK 根目录 | `ANDROID_NDK_HOME` 或 `ANDROID_NDK_ROOT` | `--ndk` |
| SDK 平台目录名 | 默认 `android-37`，兼容已安装的 `android-37.0` | `--platform` |
| Build Tools 版本 | 默认 `37.0.0` | `--build-tools` |
| 签名密钥库 | `ACO_KEYSTORE` | `--keystore` |
| 密钥库密码文件 | `ACO_KEYSTORE_PASSWORD_FILE` | `--password-file` |
| 密钥别名 | `ACO_KEY_ALIAS`，默认 `aco` | `--key-alias` |

签名材料由构建者自行提供，密码文件为 UTF-8 文本。覆盖安装已有应用需要相同签名；签名材料不随源码分发。

设置上述环境变量后，在项目根目录运行：

```sh
python tools/build.py --check
python tools/build.py
```

`--check` 只验证配置与项目资源，不执行构建。配置及必要文件会在清理构建目录前检查。完整参数见 `python tools/build.py --help`。

脚本按运行系统选择 Windows、Linux 或 macOS 的工具文件名及 NDK 主机目录。Windows 是项目已有的实际构建环境，其他主机环境需自行验证。

APK 与 SHA-256 校验文件输出到 `dist/`，不自动安装或上传。

## 可选字体处理

仓库包含构建所需的字体资源和生成后的 `PowerGlyphs.java`，普通构建不必重新生成。修改字体时，额外安装：

```sh
python -m pip install -r tools/requirements-fonts.txt
```

- `subset-brand-font.py`：输入 Nunito 原始字体，生成字标字体子集。
- `subset-cjk-font.py`：输入源泉圆体原始粗体，生成中文标题字体子集。
- `generate-power-glyphs.py`：从项目字标字体生成 root 功耗显示路径；支持 `--font` 和 `--output`。

这些工具按脚本所在位置定位项目，不要求从项目上级目录执行。例如：

```sh
python tools/subset-brand-font.py /path/to/Nunito.ttf
python tools/subset-cjk-font.py /path/to/GenSenRounded-Bold.otf
python tools/generate-power-glyphs.py
```

重新生成资源时保留原字体版权及 OFL 许可文件，相关来源见项目的 `THIRD_PARTY_NOTICES.md`。
