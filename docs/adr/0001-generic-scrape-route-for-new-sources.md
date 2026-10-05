# generic-scrape-route-for-new-sources

Status: accepted (direction decided; implementation deferred)

新增一个不提供标准 RSS/Atom 的信息源，目前必须写一个新的 `RouteHandler` 类并重新编译部署，这是当前"降低易用性门槛"目标下唯一的结构性瓶颈（而非 UI 打磨问题）。我们决定后续新增一个**通用 CSS 选择器路由**——由 `RouteConfig` 中的选择器字段（如 `listSelector`/`titleSelector`/`linkSelector`）驱动的单个 `RouteHandler` 实现，用户在 `/index` 后台填选择器即可加源，不需要写代码；而不是引入动态插件加载或 RSSHub 式的 Radar 规则/DSL 抓取引擎。

前者只是把现有 `/rss` 路由"用 `feedUrl` 参数配置一个类"的模式，延伸成"用选择器参数配置另一个类"，认知成本增量小，且不违反 `CONTEXT.md` 中"不做动态插件加载/classpath scanning"这条 Non-Goal——注册的仍然是一个提前编译好的固定类，只是它的抓取目标由配置数据驱动。后者会引入任意脚本执行面和显著的维护复杂度，直接落入"不做完整 RSSHub 通用参数兼容（Radar rules/OpenCC/custom filters）"这条已有 Non-Goal 的范围。

## Considered Options

维持现状（不做）：
  优点: 零成本，不引入新代码路径
  缺点: 不解决"加源门槛"，放弃了这个易用性目标

通用 CSS 选择器路由（本决策选择）：
  优点: 认知成本低，复用 `/rss` 的配置驱动模式；不违反现有 Non-Goals
  缺点: 覆盖面有限——无法处理需要 JS 渲染、登录态或强反爬的站点，这些仍需手写 `RouteHandler`

脚本化/DSL 抓取引擎（类 RSSHub Radar）：
  优点: 覆盖面最广
  缺点: 引入任意代码执行面，维护成本陡增，直接违反已有 Non-Goals

## Consequences

- 实现时需要在 `RouteConfig` 增加选择器相关字段，并在 `CONTEXT.md` 的 Domain Terms 里补一条定义（参照现有 "Generic RSS route" 的写法）。
- 不承诺覆盖需要 JS 渲染、登录态、强反爬的站点——这些场景仍然需要新增专用 `RouteHandler` 类，Route Implementation Policy 不变。
- 本决策不修改 `CONTEXT.md` 现有 Non-Goals 的字面表述，因为通用选择器路由本身不构成"动态插件加载"或"完整 RSSHub 参数兼容"；实现落地时再补充 Domain Terms。
