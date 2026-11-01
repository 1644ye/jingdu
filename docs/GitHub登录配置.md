# GitHub 登录配置

书源仓库：https://github.com/your-account/reading-library

进度仓库：https://github.com/your-account/reading-state

正式登录需要两套独立 GitHub App，均启用 Device flow：

1. 书源 App：Repository permissions → Contents：Read-only，仅安装到 reading-library。
2. 进度 App：Repository permissions → Contents：Read and write，仅安装到 reading-state。建议此仓库设为私有。

已接入用户提供的两个公开 Client ID：书源 `Iv23liwlcvuZlOilMie3`，同步 `Iv23liNWpFfrzUs2jRqb`。不需要把 Client Secret 或私钥打包进 APK。

后续更换书源 owner/repo 不需要重新构建 APK，但私有仓库必须在只读授权范围内。不能把书源仓库安装到具有内容写权限的进度 App。

## 操作顺序

1. 更多 → GitHub 书库与进度同步 → 书源账号与仓库设置，登录只读书源应用。在浏览器输入设备码后回到 App，点击“我已授权，等待确认”。确认实际账号。
2. 填写 owner、仓库、分支、书源根目录及索引路径，或从已授权仓库选择，点击“连接并保存”。默认书源为 `your-account/reading-library`。
3. 回到 GitHub 书库入口，浏览索引、选择书籍或目录下载；索引尚未生成时可直接浏览真实目录。
4. 在电脑运行 `python tools/generate_catalog.py 书源根目录 --library-id personal-library` 生成 catalog.json，将其与正文一起提交书源仓库。远程书库要求每个文件包含一本书。
5. 在“进度账号与仓库设置”独立登录同步应用，连接并保存 `your-account/reading-state`。该仓库必须私有，应用可以初始化空同步仓库的进度文件。
6. 打开阅读进度同步，确认仓库后启用同步。更换同步账号或仓库会暂停同步，需要重新确认开启。
7. 在另一台设备使用同一书源与同步仓库，下载相应书籍并同步。离线产生两个候选位置时，在冲突列表明确选择；正文版本不同时尝试文字定位，无法定位则先在本机选择章节，再采用本机位置。

授权失效时需要重新登录；本版不在 APK 中保存客户端密钥来静默刷新令牌。同步失败保留本地数据和待同步状态。后台受 Android 调度限制，退出阅读不保证立即完成上传。

书源切换保留已下载书籍与原来源身份；不同仓库同 ID 不覆盖。个人添加的标签在更新时保留。当前同步范围仅为远程来源书籍的阅读位置与阅读状态。

开发代码与配套测试已编写；进入统一测试阶段后再记录实际结果，不能以编译通过替代登录和跨设备验收。
