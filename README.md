# SkyTakeOut-AI · 外卖平台 AI 智能客服

基于 **Spring Boot 3 + Spring AI 1.1** 自研多智能体（Multi-Agent）框架的外卖平台智能客服。
用户以自然语言对话即可完成 **查菜品 / 查套餐 / 下单 / 查单 / 退单 / 联网搜索 / 文件与 PDF 报告生成** 等操作，
全程 SSE 流式输出思考过程与执行结果。

> 本项目在「苍穹外卖」业务模型之上，从零实现了路由、规划、执行、蒸馏、汇总的完整 Agent 链路，
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
                          ├─ ReAct 执行：Kanodays88Manus（步数预算 + 卡死检测）
                          ├─ 结果蒸馏：双副本（结构化核心结果进上下文 / 原始结果归档）
                          └─ 汇总：fuseResults 整合为最终报告
```

### 智能体类体系

| 类 | 职责 |
|---|---|
| `BaseAgent` | 执行循环骨架：状态机、最大步数预算、卡死检测（重复输出 3 次自动换策略） |
| `ReActAgent` | ReAct 循环：思考 → 行动 → 观察 |
| `ToolCallAgent` | 工具调用执行（基于 Spring AI `ToolCallback`） |
| `Kanodays88Manus` | 具体执行智能体，按子任务契约运行 |
| `RouterAgent` | 意图路由，`BeanOutputConverter` 结构化输出 |
| `SimpleChatAgent` | 简单对话直通 |

### 业务技能（Agent Skills，`resources/skills/`）

采用 Anthropic Agent Skill 的三层渐进式上下文加载，最大化节约 Token：

1. **元数据层**：YAML frontmatter（名称/描述/必需参数），路由时只加载这一层
2. **定义层**：SKILL.md 正文，命中后再注入
3. **执行层**：`references/` 参考文档，Agent 执行中按需加载

| 技能 | 说明 |
|---|---|
| `food_delivery/query_dish_or_setmeal` | 查询菜品与套餐 |
| `food_delivery/place_order` | 下单 |
| `food_delivery/query_order` | 查询订单 |
| `food_delivery/cancel_order` | 取消/删除订单 |
| `tarvel_plan/make_tarvel_plan` | 出行规划（联网搜索示例） |

### 工具集（`@Tool`，Spring AI 自动注册）

菜品查询、套餐查询、下单、查单、删单、联网搜索（内置 RAG 二次提纯）、
文件读写（会话目录隔离）、PDF 报告生成、资源下载、任务终止。

## 关键设计亮点

1. **子任务契约**：分解时为每个子任务声明 `downstreamTaskIds`（所有下游依赖，不止紧邻）与
   `coreContent`（下游必需的核心输出）。蒸馏时以此为约束，解决"多级蒸馏后下游需要的信息被删掉"的问题。
2. **拓扑分层并行**：依据契约反向推导依赖图，构建执行波次（wave），同层子任务线程池并发执行，
   层间串行等待，无环保证 + 循环依赖兜底。
3. **蒸馏双副本**：`structuredCoreResult`（压缩后进上下文）+ `rawResult`（原始结果归档兜底）；
   结果本身已足够结构化（<2000 字符）时跳过蒸馏，省一次 LLM 调用。
4. **自研 ReAct 循环**：步数预算防失控；连续 3 次重复输出判定卡死，自动注入提示换策略。
5. **三层渐进式技能加载**：见上，路由阶段只花元数据的 Token。
6. **会话记忆与多租户隔离**：`FileBasedChatMemory`（Kryo 序列化落盘，取最近 10 条）+
   Redis TTL 登记 + 定时任务清理；向量库按 `user`/`chat_id` 元数据过滤，用户之间互不可见。
7. **缓存三件套**：菜品/套餐查询 Cache-Aside + 缓存 key 级分布式锁（Redisson）+ TTL +
   空结果短缓存防穿透 + 抢锁超时降级直查。
8. **安全**：订单全链路绑定登录用户（防横向越权）；工具文件操作限制在
   `用户/会话` 目录内（防路径穿越）；统一响应体 + 全局异常处理。
9. **可运维的并发模型**：全部智能体任务收敛到一个 Spring 管理的共享线程池
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

### 1. 环境准备

- JDK 17+、Maven 3.8+、MySQL 8.0+、Redis 6+
- DeepSeek API Key（对话模型）
- 阿里百炼（DashScope）API Key（Embedding 模型 `text-embedding-v4`）
- （可选）百度搜索 API Key（联网搜索工具）

### 2. 初始化数据库

```bash
mysql -uroot -p < db/init.sql
```

脚本会创建 `sky_take_out` 库、7 张表（user / category / dish / setmeal / setmeal_dish /
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
mvn package -DskipTests && java -jar target/sky-take-out-AI-0.0.1-SNAPSHOT.jar
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

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/user/login` | 登录（未注册自动注册） |
| GET | `/ai/chat/{msg}` | 智能体对话（SSE，自动意图路由） |
| GET | `/ai/chat/rag/{msg}` | 上传文档的 RAG 问答（SSE） |
| GET | `/ai/chat/history` | 当前用户的会话列表 |
| GET | `/ai/chat/history/{chatId}` | 指定会话的历史消息 |
| DELETE | `/ai/chat/history/{chatId}` | 删除指定会话（记忆 + 文件） |
| POST | `/file/upload` | 上传 PDF 文档入知识库 |

## 目录结构

```
src/main/java/com/kanodays88/skytakeoutai/
├── agent/
│   ├── BaseAgent / ReActAgent / ToolCallAgent / Kanodays88Manus   # 智能体继承体系
│   ├── plan/      # PlanExecute 规划执行 + SubTask 子任务契约
│   ├── router/    # RouterAgent 意图路由
│   ├── rag/       # RAG 检索问答
│   ├── simpleChat/# 简单对话
│   └── sse/       # SSE 事件推送
├── skill/         # SkillLoader / SkillRegistry，Agent Skill 加载与检索
├── tools/         # @Tool 业务工具集
├── memory/        # FileBasedChatMemory（Kryo 落盘）
├── controller/    # Chat / User / FileUpload
├── service/       # 业务服务
├── interceptor/   # 登录态校验与续期
├── config/        # Redis / Redisson / MVC / ChatClient / AsyncConfig 线程池
├── common/        # 统一响应体 Result / 错误码 / 人设
├── exception/     # 业务异常 + 全局异常处理
├── timedTask/     # 过期会话与向量数据清理
└── content/       # ThreadLocal 上下文（用户 / 会话）
```

## 已知限制（Roadmap）

- 向量库当前使用 `SimpleVectorStore`（内存态，重启即失），规划迁移 PgVector / Redis Stack
- 会话记忆存本地磁盘，多实例部署时需共享存储
- 下单金额由模型传参计算，未做服务端按 DB 单价重算（后续可在 OrderTool 内回查价格）

## 测试

纯单元测试（不依赖数据库与外部服务，可直接运行）：

```bash
mvn test -Dtest='SkillRegistryTest,PlanExecuteTest'
```

- `SkillRegistryTest`：中英混合分词与技能匹配（回归锁定中文匹配修复）
- `PlanExecuteTest`：拓扑分层波次构建（线性/并行/菱形/循环依赖）、LLM 分解结果降级兜底、契约工具筛选

`SkyTakeOutAiApplicationTests#contextLoads` 为 Spring 上下文冒烟测试，需要本地 MySQL / Redis / 模型 Key 就绪。
