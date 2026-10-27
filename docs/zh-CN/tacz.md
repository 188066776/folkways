# TACZ 枪械

[文档](README.md) · [English](../en/tacz.md)

将 `folkways-tacz-*.jar` 与 Core、Vanilla 及 **NeoForge 1.21.1 非官方 TACZ
移植版 1.1.8-hotfix-r7** 一同安装。`blockwright.toml` 固定下载地址和 SHA-512。
本集成可选，且不依赖 RTS。

在聚落书的**配装**页创建配装，用**＋ 添加 → 武器型号**选择受支持枪型。
型号保留 GunId，允许常规配件变体，不固定弹匣和弹膛状态；爆炸、燃烧配件仍不可用。
通过**分配居民…**逐人选择配装。旧存档的精确物品和附魔限定加载为普通物品／标签；
旧手持枪械的配件限定移除，枪型和弹药身份保留。

添加枪械会预填对应口径的弹药补给条目，通过 `custom_data.AmmoId` 区分。
初始建议数量为三个弹匣；之后由玩家设置配置条目的目标量和补给阈值。
全局 `targetRounds`、`lowWaterRounds` 设置已移除。弹匣与弹膛中的子弹计入总量，
只有严格低于阈值时才从可到达的登记仓储补足，始终受实际库存限制。
手动给予枪械不会自动推断配置。枪械领取使用配装，不受工种工具列表控制。

给居民分配含枪械的配置后，可将其派驻到绘制的**哨位**区域。
空枪或缺少弹药不阻止派驻；居民领取配置装备后，在视线和有效射程内向敌对目标开火，
不会追出哨区。配置与补给状态显示装备不可用及缺枪缺弹，哨区卡片显示有效值勤与射线受阻；断供时仍可能显示观察中。
原有巡逻居民也能使用相同枪械会话。可选 RTS 的 GUARD 仍保留原有十二格驻点限制；
本集成不实现 RTS 显式指定目标攻击授权。

物品使用提供器报告直射远程武器、AmmoId、弹匣容量、已装弹数，以及基于 RPM、
手动枪机和点射间隔的周期。有效射程不超过弹速乘寿命、服务器上限及各枪型上限。
这些元数据不会代替 TACZ 自己的射击和换弹计时。

服务器配置文件为 `folkways-tacz-server.toml`：

| 设置                  | 默认值                          | 含义                                                 |
| --------------------- | ------------------------------- | ---------------------------------------------------- |
| `allowedGuns`         | `[]`                            | 兼容内置枪械；显式列表可限制枪型并启用常规第三方枪包 |
| `rangeCap`            | `24`                            | 总射程上限，同时受枪械数据限制                       |
| `gunRangeCaps`        | `tacz:m1911=16`, `tacz:m870=12` | 各枪型上限，不超过总上限                             |
| `protectColonyAllies` | `true`                          | 保护同殖民地居民和宠物，也适用于已经飞出的殖民地子弹 |

友军居民、玩家和殖民地宠物进入射线会阻止开火。流弹撞击方块后停止，
不打碎玻璃或触发方块。爆炸、燃烧、燃料、虚拟或无限弹药、蓄力武器和未知脚本不受支持。
配件效果在装备与发射时检查，最终弹丸在生成前再次检查。
绘枪、瞄准、射击、拉栓和换弹均由 TACZ 的 `IGunOperator` 执行；
换弹 mixin 将脚本装弹限制为背包实际扣除的子弹。延迟点射保留原会话代次，
每发重新检查实时授权、视线、射程和友军射线。

便携枪械通过 `WeaponAdapter`/`WeaponSession` 接入；哨位提供实时 `spottedTarget` 授权。
枪械始终需要自身视线，不提供间接射击，不注册固定 `WeaponStations`，
不改变殖民地共享观察、哨位容量、手动命令优先级或 RTS 输入。

持枪姿态覆盖玩家皮肤和常见 Geo／女仆手臂骨骼，并显示实际手持枪械。
TACZ 玩家专属换弹动画、模型专用动画、其他居民类型、固定枪站和间接武器暂缓。

```sh
./gradlew -PtaczTests=true -PgameTestMod=tacz runGameTestServer
./gradlew -PtaczTests=true runGameTestServer
xvfb-run -a npm run test:e2e -- tests/e2e/tacz-combat.spec.ts --workers=1
```

Blockwright 提供 `tacz` 与 `tacz_models` 配置；后者额外安装 Touhou Little Maid，
用于皮肤、Geo 和女仆持枪验收。TACZ 原生计时使用毫秒，不能只靠加速 GameTest 刻推进。

## 移植验证结果

Linux、JDK 21，使用 `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` 和
`GRADLE_USER_HOME=/tmp/folkways-sentry-gradle`：

| 命令                                                                        | 结果                                 |
| --------------------------------------------------------------------------- | ------------------------------------ |
| `./gradlew build`                                                           | 通过；50 个任务，无失败              |
| `./gradlew runGameTestServer`                                               | 通过；1,233 项必需测试，0 失败       |
| `./gradlew -PtaczTests=true runGameTestServer`                              | 通过；1,274 项必需测试，0 失败       |
| `./gradlew -PtaczTests=true -PgameTestMod=tacz runGameTestServer`           | 通过；41 项必需测试，0 失败          |
| `npm run test:i18n`                                                         | 通过；1 个文件、8 个用例，0 失败     |
| `npm run typecheck`                                                         | 通过；7 个项目，0 错误               |
| `npm run format:md:check`                                                   | 通过；无待格式化文件                 |
| `xvfb-run -a npm run test:e2e -- tests/e2e/tacz-combat.spec.ts --workers=1` | 通过；1 项测试，0 失败，耗时 37.9 秒 |

客户端还使用 `LIBGL_ALWAYS_SOFTWARE=1`，验证哨位实际伤害目标，以及皮肤、Geo 和女仆
外观持有真实枪械并瞄准。截图位于 `tests/out/e2e/playwright/`，测试还附加姿态 JSON。
该场景没有交互式操作新增枪型弹窗，此项验收仍未运行。最终各轮无失败测试名。

旧 PR 推断手动枪械全局 32/8 补给策略的测试已改为已分配配置、严格可编辑阈值与哨位领取。
RTS 撤退布景改为会话抢占、配置撤销和撤哨，测试不安装 RTS。
新增用例覆盖元数据、带组件的配置页操作、换弹后配件身份、自身视线／射程、实时观察撤销和离籍。
静态设计批准记录在[战斗架构](combat-architecture.md)中。
