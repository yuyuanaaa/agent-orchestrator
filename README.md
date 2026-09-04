# Agent-Orchestrator · 自研多智能体任务编排系统

[![Java](https://img.shields.io/badge/Java-17-blue?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1-6DB33F?logo=spring&logoColor=white)](https://spring.io/projects/spring-ai)
[![Build Status](https://img.shields.io/github/actions/workflow/status/yuyuanaaa/agent-orchestrator/ci.yml?branch=master&logo=githubactions&logoColor=white)](https://github.com/yuyuanaaa/agent-orchestrator/actions)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

基于 **Spring Boot 3 + Spring AI 1.1** 自研多智能体（Multi-Agent）框架的通用任务编排系统。
业务侧以"商家服务平台"为最小演示场景（菜品/套餐/订单/退单），用户以自然语言对话即可完成 **查菜品 / 查套餐 / 下单 / 查单 / 退单 / 联网搜索 / 文件与 PDF 报告生成** 等操作，全程 SSE 流式输出思考过程与执行结果。

> 从零实现路由、规划、执行、蒸馏、汇总的完整 Agent 链路，
> 不依赖任何 Agent 编排框架（Dify / LangChain4j 等），所有机制均为手写。

## 核心架构

```
用户提问 (SSE)
    │
    ▼
ChatController ──► RouterAgent（两次 LLM 调用）
    │                 ├─ 1. Skill 检索 + 意图分类（结构化输出）
    │                 └─ 2. 信息是否充分 → SIMPLE_CHAT / COMPLEX_TASK / AMBIGUOUS
    │
    ├─ SIMPLE_CHAT  ──► SimpleChatAgent（单轮对话 + 工具调用）
    ├─ AMBIGUOUS    ──► 反问用户补全关键信息
    └─ COMPLEX_TASK ──► PlanExecute（规划-执行）
                          ├─ 任务分解：带下游契约的子任务列表（SubTask）
                          ├─ 拓扑分层：按依赖关系构建并行执行波次（wave）
                          ├─ ReAct 执行：PlanAgent（步数预算 + 卡死检测）
                          ├─ 结果蒸馏：结构化核心结果进上下文 / 原始结果留存排查
                          └─ 汇总：fuseResults 整合为最终报告
```

### 智能体类体系

| 类 | 职责 |
|---|---|
| `BaseAgent` | 执行循环骨架：状态机、最大步数预算、卡死检测（重复输出 3 次自动换策略） |
| `ReActAgent` | ReAct 循环：思考 → 行动 → 观察 |
| `ToolCallAgent` | 工具调用执行（基于 Spring AI `ToolCallback`） |
| `PlanAgent` | 具体执行智能体，按子任务契约运行 |
| `RouterAgent` | 意图路由，`BeanOutputConverter` 结构化输出 |
| `SimpleChatAgent` | 简单对话直通 |

### 业务技能（Agent Skills，`resources/skills/`）

采用 Anthropic Agent Skill 的三层渐进式上下文加载，最大化节约 Token：

1. **元数据层**：YAML frontmatter（名称/描述/必需参数），路由时只加载这一层
2. **定义层**：SKILL.md 正文，命中后再注入
3. **执行层**：`references/` 参考文档，Agent 执行中按需加载

| 技能 | 说明 |
|---|---|
| `commerce/query_dish_or_setmeal` | 查询菜品与套餐 |
| `commerce/place_order` | 下单 |
| `commerce/query_order` | 查询订单 |
| `commerce/cancel_order` | 取消/删除订单 |
| `travel_plan/make_travel_plan` | 出行规划（联网搜索示例） |

### 工具集（`@Tool`，Spring AI 自动注册）

菜品查询、套餐查询、下单、查单、删单、联网搜索（内置 RAG 二次提纯）、
文件读写（会话目录隔离）、PDF 报告生成、资源下载、任务终止。

## 关键设计亮点

1. **子任务契约**：分解时为每个子任务声明 `downstreamTaskIds`（所有下游依赖，不止紧邻）与
   `coreContent`（下游必需的核心输出）。蒸馏时以此为约束，解决"多级蒸馏后下游需要的信息被删掉"的问题。
2. **拓扑分层并行**：依据契约反向推导依赖图，构建执行波次（wave），同层子任务线程池并发执行，
   层间串行等待，无环保证 + 循环依赖兜底。
3. **蒸馏双副本**：`structuredCoreResult`（压缩后进上下文）+ `rawResult`（原始结果留存，用于排查与后续召回）；
   结果本身已足够结构化（<2000 字符）时跳过蒸馏，省一次 LLM 调用。
4. **自研 ReAct 循环**：步数预算防失控；连续 3 次重复输出判定卡死，自动注入提示换策略。
5. **三层渐进式技能加载**：见上，路由阶段只花元数据的 Token。
6. **会话记忆与多租户隔离**：`RedisChatMemory`（Redis 存储，Kryo+Base64 序列化，取最近 10 条，
   多实例共享）+ Redis TTL 登记 + 定时任务清理；向量库按 `user`/`chat_id` 元数据过滤，用户之间互不可见。
7. **向量库持久化**：`SimpleVectorStore` 快照落盘（`tmp/vector-store.json`）+ 启动自动加载 +
   内置知识首次启动自动写入（幂等），关闭/定时双通道持久化，解决"重启即失"。
8. **缓存三件套**：菜品/套餐查询 Cache-Aside + 缓存 key 级分布式锁（Redisson）+ TTL +
   空结果短缓存防穿透 + 抢锁超时降级直查。
9. **安全**：订单全链路绑定登录用户（防横向越权）；下单金额由服务端按数据库单价重算，
   不信任模型传入的价格（防模型算错价 / 虚构菜品 / 对停售商品下单）；工具文件操作限制在
   `用户/会话` 目录内（防路径穿越）；统一响应体 + 全局异常处理。
10. **分层鉴权**：`RefreshTokenInterceptor`（还原登录态）→ `LoginInterceptor`（登录校验）→
   `AdminInterceptor`（管理员角色校验）三级拦截链；管理端 `/ai/admin/**` 仅管理员可访问，
   管理员由配置白名单 `app.admin.usernames`（环境变量 `ADMIN_USERNAMES`）驱动，角色不落库、
   登录时实时判定，避免历史账号缺角色字段。
11. **可运维的并发模型**：全部智能体任务收敛到一个 Spring 管理的共享线程池
   （线程命名、有界队列、CallerRunsPolicy 背压、优雅停机），替代"每个 wave 临时建池用完即弃"；
   Agent 实例化统一收敛到 `AgentFactory`，依赖注入与运行时参数分离。

## 技术栈

| 分类 | 选型 |
|---|---|
| 语言 / 框架 | Java 17 · Spring Boot 3.5 |
| AI 框架 | Spring AI 1.1（OpenAI 协议） |
| 模型 | DeepSeek（对话）· 阿里百炼 text-embedding-v4（向量） |
| ORM | MyBatis-Plus |
| 缓存 / 锁 | Redis · Redisson |
| 序列化 | Kryo（会话记忆）· Hutool JSON |
| 文档 / 接口 | SpringDoc + Swagger UI（OpenAPI 3）· iText（PDF 生成） |
| 构建 | Maven |

## 快速启动

### 方式一：Docker 一键部署（推荐）

前置：安装 Docker 与 Docker Compose（v2.20+）。编排文件位于仓库根目录 `./docker-compose.yml`，与前端 `frontend/` 同级（后端即为本仓库根目录，含 `pom.xml` / `src/` / `Dockerfile`），用于同时编排后端与前端。

```bash
# 1. 配置密钥
cp .env.example .env      # 编辑 .env 填入 DASHSCOPE_API_KEY（必填）

# 2. 一键构建并启动 MySQL + Redis + 后端 + 前端
docker compose up -d --build

# 3. 查看状态与日志
docker compose ps
docker compose logs -f backend
```

启动完成后：

- 前端页面：http://localhost（nginx，80 端口）
- 后端接口：http://localhost:8080（context-path=/api）
- 接口文档：http://localhost:8080/swagger-ui.html

MySQL 首次启动会自动执行 `db/init.sql` 建库建表并写入示例数据（库名 `agent_platform`）。
上传的 PDF 与会话记忆通过 `backend-tmp` 卷持久化，重建容器不丢。

停止：`docker compose down`；连同数据卷一起清理：`docker compose down -v`。

### 方式二：本地手动启动

### 1. 环境准备

- JDK 17+、Maven 3.8+、MySQL 8.0+、Redis 6+
- DeepSeek API Key（对话模型）
- 阿里百炼（DashScope）API Key（Embedding 模型 `text-embedding-v4`）
- （可选）百度搜索 API Key（联网搜索工具）

### 2. 初始化数据库

```bash
mysql -uroot -p < db/init.sql
```

脚本会创建 `agent_platform` 库、7 张表（user / category / dish / setmeal / setmeal_dish /
orders / order_detail）以及可供体验的示例菜品与套餐数据。

### 3. 配置密钥

复制密钥样例并填入真实 Key（该文件已被 .gitignore 忽略，不会提交）：

```bash
cp src/main/resources/secrets.yaml.example src/main/resources/secrets.yaml
```

```yaml
# secrets.yaml
DEEPSEEK_API_KEY: sk-xxx
EMBEDDING_API_KEY: sk-xxx
MYSQL_PASSWORD: your-mysql-password
# 可选
REDIS_PASSWORD:
BAIDU_API_KEY: xxx
```

所有配置均支持环境变量覆盖（见 `application.yaml`），生产环境建议直接用环境变量注入。

### 4. 启动

```bash
mvn spring-boot:run
# 或
mvn package -DskipTests && java -jar target/agent-orchestrator-0.0.1-SNAPSHOT.jar
```

启动后访问：

- 接口文档：http://localhost:8080/swagger-ui.html
- 健康检查与接口调试：见文档中 `user` / `file` / `ai/chat` 分组

### 5. 体验对话

1. `POST /user/login`：新用户名会自动注册并返回 token（密码 BCrypt 存储）
2. 携带请求头 `Authorization: <token>` 与 `chatId: <任意会话id>`，调用
   `GET /ai/chat/{msg}`（SSE 流式），例如：
   - 「帮我看看有什么 20 块以内的主食」→ 简单任务直查
   - 「查一下今天的天气，再生成一份 PDF 出行建议」→ 复杂任务规划并行执行
3. `GET /ai/chat/rag/{msg}`：基于已上传 PDF 的知识库问答

## 接口一览

> 表中路径为 Controller 映射路径，实际访问需叠加 context-path `/api`（如 `/api/ai/chat/...`）。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/ai/user/login` | 登录（未注册自动注册） |
| GET | `/ai/chat/{msg}` | 智能体对话（SSE，自动意图路由） |
| GET | `/ai/chat/rag/{msg}` | 上传文档的 RAG 问答（SSE） |
| GET | `/ai/chat/history` | 当前用户的会话列表 |
| GET | `/ai/chat/history/{chatId}` | 指定会话的历史消息 |
| DELETE | `/ai/chat/history/{chatId}` | 删除指定会话（记忆 + 文件） |
| POST | `/ai/upload/pdf/{chatId}` | 上传 PDF 文档入会话知识库 |
| GET | `/ai/upload/list/{chatId}` | 查询会话已上传文件列表 |
| GET | `/ai/upload/download/{chatId}/{fileName}` | 下载会话已上传的 PDF |
| DELETE | `/ai/upload/{chatId}/{fileName}` | 删除会话已上传的 PDF（同时清理磁盘文件与向量） |
| GET | `/ai/shop/dish/list` | 查询全部菜品（含描述/停售标记） |
| GET | `/ai/shop/setmeal/list` | 查询全部套餐（含所含菜品） |
| GET | `/ai/shop/order/list` | 查询当前登录用户订单 |
| POST | `/ai/admin/image/upload` | 管理端图片上传（落盘 `tmp/upload/`） |
| GET | `/ai/admin/category/list` | 管理端分类列表（菜品/套餐两类） |
| POST | `/ai/admin/category` | 新增分类 |
| PUT | `/ai/admin/category` | 修改分类 |
| DELETE | `/ai/admin/category/{id}` | 删除分类（有菜品/套餐关联时拒绝） |
| GET | `/ai/admin/dish/list` | 管理端菜品列表（含完整图片 URL） |
| POST | `/ai/admin/dish` | 新增菜品 |
| PUT | `/ai/admin/dish` | 修改菜品 |
| DELETE | `/ai/admin/dish/{id}` | 删除菜品（级联删除套餐引用） |
| GET | `/ai/admin/setmeal/list` | 管理端套餐列表（含所含菜品与份数） |
| POST | `/ai/admin/setmeal` | 新增套餐（重建套餐-菜品关联） |
| PUT | `/ai/admin/setmeal` | 修改套餐 |
| DELETE | `/ai/admin/setmeal/{id}` | 删除套餐 |

`/ai/shop/**` 为前端图形化点餐界面提供的只读查询接口，复用 `DishTool` / `SetmealTool` / `OrderTool`
的缓存与归属校验逻辑，与对话链路返回的数据保持一致。

`/ai/admin/**` 为管理端后台的增删改查接口，用于维护分类 / 菜品 / 套餐数据；上传图片存文件系统
（`tmp/upload/`，经 `/upload/**` 静态映射访问），使菜单数据可自维护，形成完整产品闭环。

## 目录结构

```
src/main/java/com/agentorchestrator/platform/
├── agent/
│   ├── BaseAgent / ReActAgent / ToolCallAgent / PlanAgent         # 智能体继承体系
│   ├── plan/      # PlanExecute 规划执行 + SubTask 子任务契约
│   ├── router/    # RouterAgent 意图路由
│   ├── rag/       # RAG 检索问答
│   ├── simpleChat/# 简单对话
│   └── sse/       # SSE 事件推送
├── skill/         # SkillLoader / SkillRegistry，Agent Skill 加载与检索
├── tools/         # @Tool 业务工具集
├── memory/        # RedisChatMemory（Kryo + Base64 存 Redis，多实例共享）
├── controller/    # Chat / User / FileUpload / Shop / Admin
├── service/       # 业务服务
├── interceptor/   # 登录态还原 / 登录校验 / 管理员角色校验
├── config/        # Redis / Redisson / MVC / ChatClient / AsyncConfig 线程池
├── common/        # 统一响应体 Result / 错误码 / 人设
├── exception/     # 业务异常 + 全局异常处理
├── timedTask/     # 过期会话与向量数据清理
└── content/       # ThreadLocal 上下文（用户 / 会话）
```

## 已知限制（Roadmap）

- 向量库基于 `SimpleVectorStore`，通过快照落盘（`tmp/vector-store.json`）实现单实例持久化，
  重启自动恢复，内置知识（FAQ / 菜品口味 PDF）在首次启动自动写入；多实例部署时仍需共享存储，
  规划迁移 Redis Stack / PgVector
- 上传/生成的本地文件（PDF、图片、会话记忆的历史文件）存本地磁盘，多实例部署时需共享文件系统
  （NFS / OSS / MinIO）

## 测试

共 31 个测试（含 3 个性能基准），全部不依赖外部 LLM / 真实 MySQL，可一键复跑：

```bash
./mvnw test
```

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `SkillRegistryTest` | 3 | 中英混合分词、技能匹配、Frontmatter 解析 |
| `PlanExecuteTest` | 9 | 拓扑分层（线性/并行/菱形/循环依赖）、LLM 分解降级、契约工具筛选 |
| `OrderToolTest` | 5 | 订单详情回填、`NOT_A_DISH` 哨兵、null/空列表容错、多条目不覆盖 |
| `DishToolTest` | 4 | 类别名批量回填（N+1 修复回归）、id 去重、空结果不查库 |
| `MenuCacheServiceTest` | 5 | SCAN 替代 KEYS、连带失效策略、空 key 不删、Redis 异常降级 |
| `WebSearchToolTest` | 1 | 联网搜索工具加载冒烟 |
| `AgentPlatformApplicationTests` | 1 | Spring 上下文冒烟（需本地 MySQL/Redis/模型 Key） |
| `TopologyParallelBenchmark` | 1 | 拓扑并行加速比（性能基准） |
| `SkillTokenBenchmark` | 1 | 三层技能加载 token 节省（性能基准） |
| `CacheScanBenchmark` | 1 | SCAN vs KEYS 阻塞对比（性能基准，需 Redis 在线） |

### 性能基准（简历量化数据来源）

| 基准 | 实测结果 | 关键证据 |
|---|---|---|
| `SkillTokenBenchmark` | 路由阶段 **节省 88.8% 字符 ≈ 708–1,417 tokens/次** | 5 个 SKILL.md 共 3,194 字符，元数据层仅 359 字符 |
| `TopologyParallelBenchmark` | N=8 无依赖子任务，**加速 8.10x**（1,628ms → 201ms） | 每任务模拟 200ms × 3 次取平均 |
| `CacheScanBenchmark` | 5,000 key 批量失效，SCAN 8ms / KEYS 4ms（KEYS 阻塞 Redis 主线程） | 触发但不通过，SCAN 才是正确默认 |

三个基准与 Surefire `**/*Benchmark.java` 已纳入 `mvn test` 日常回归，CI 跑全量测试时同时验证。
