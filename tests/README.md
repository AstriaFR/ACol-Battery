# 测试目录

本目录保留规则、交互和数据安全方面的行为测试。固定字号、颜色常量、说明文案及源码排列顺序不作为自动化断言。

主题测试只关注亮暗状态、页面同步和系统栏更新时序，不校验具体配色。图表命中、滚动位置与可见性属于交互逻辑，继续保留相应检查。

## 前端测试入口

| 入口 | 覆盖范围 |
| --- | --- |
| `charts-test.cjs` | 图表量程、命中、温度数据、息屏投影及其缓存时机 |
| `settings-test.cjs` | 采样间隔、确认取消、记录开关及勾选记忆 |
| `records-test.cjs` | 实时游标、记录筛选、分页、异步响应及容量变化显示 |
| `startup-test.cjs` | 初始化进度、错误保留、降级提示及前台请求超时 |
| `theme-test.cjs` | 系统与手动主题、嵌入页同步、系统栏更新时序及图表状态保留 |
| `record-energy-test.cjs` | 分时耗电缓存、归属修补、并发读取及手势 |
| `battery-explanation-test.cjs` | 电池说明弹窗的焦点与页面滚动保留 |
| `edge-scrollbar-test.cjs` | 滚动指示器的比例、边界、显示范围和资源释放 |

`helpers.cjs` 为合并后的测试场景统一隔离浏览器和原生接口替身，不是独立测试入口。

## Java 测试分组

- 电池与采样：`CoreTest`、`BatteryDetailsTest`、`SamplingTest`、`GameSamplingTest`、`HourlyEnergyTest`、`TimelineTest`。充电尾充与屏幕状态回退统一在 `SamplingTest` 中维护。
- 记录：`RecordFilterTest`、`RecordingGateTest`、`RecoveryTest`、`RecoveryDatabaseTest`。
- 初始化与连接：`StartupDiagnosticsTest`、`ProcessResponseTest`、`RootSessionStartupTest`、`AppBridgeTest`、`FrameworkWatchTest`。启动失败与降级报告统一在 `StartupDiagnosticsTest` 中维护，命令截止时间与进程响应统一在 `ProcessResponseTest` 中维护，前后台命令路由由 `AppBridgeTest` 检查。
- 数据与运行文件：`DatabaseRebuildTest`、`SafeDatabaseDeviceTest`、`DataTransferTest`、`MaintenanceTest`、`RuntimePayloadTest`、`RuntimeTransactionTest`。
- 独立功耗显示：`PowerDisplayStateTest`。

纯规则测试和 Android 集成测试处于不同层级，不能仅因覆盖同一个功能就互相替代。数据库事务、真实 SQLite 损坏保护和 Root 会话测试应继续保留。

## 运行环境

普通 `.cjs` 文件由 Node.js 执行。`record-energy-test.cjs` 使用 VM 模块，需要以下启动参数：

```sh
node --experimental-vm-modules tests/record-energy-test.cjs
```

不依赖 Android 的 Java 规则测试可在 JDK 上执行。涉及 Android、JSON、SQLite、Binder 或文件权限的 Java 测试，需要对应运行环境或真实实现的适配层；SDK 的 `android.jar` 仅供编译，不能直接代替运行实现。
