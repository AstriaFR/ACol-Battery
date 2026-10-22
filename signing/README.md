# 本地正式签名

`tools/build.py` 默认从此目录读取 `aco.p12` 和 `password.txt`，以复用可覆盖升级的正式证书。

这两个文件包含签名私钥及密码，已由项目忽略规则排除。不要把此目录内容上传到公开仓库或随源码包分发；迁移开发环境时应单独安全备份。

也可通过 `ACO_KEYSTORE`、`ACO_KEYSTORE_PASSWORD_FILE` 和 `ACO_KEY_ALIAS` 使用外部签名材料。签名文件缺失时构建会直接失败，不会生成新密钥。
