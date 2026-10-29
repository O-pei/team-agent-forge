# AgentForge

> 开源企业级多智能体应用平台 · Open-source Enterprise Multi-Agent Platform
>
> **Agent runtime + RAG + enterprise data + secure sandbox + memory + token audit + RBAC**，基于 Spring AI Alibaba / Spring Boot 3.5 / Java 21 / Vue 3。

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring AI Alibaba](https://img.shields.io/badge/Spring%20AI%20Alibaba-1.1.2.4-blue)](https://java2ai.com/)
[![Vue](https://img.shields.io/badge/Vue-3.5-42b883)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> 状态：`v0.1.0` 开发中（脚手架阶段）。Roadmap 见 [`docs/02-开发路线图.md`](docs/02-开发路线图.md)。

---

## 为什么是 AgentForge

企业落地 Agent 的难点不在“调用一次大模型”，而在**把它变成可治理的生产系统**：权限、审计、成本、记忆、知识、数据、安全执行、可观测、可扩展。

AgentForge 把这些能力收敛成一套**模块化单体**（可按模块拆服务），让二次开发者直接站在运行时之上做业务：

| 能力 | 说明 |
| --- | --- |
| 🤖 多智能体协作 | Supervisor / Routing / Sequential / Parallel / Agent-as-Tool，基于 Spring AI Alibaba `ReactAgent` + Graph Core |
| 📚 RAG 知识增强 | Tika 解析 → 切分 → 向量化 → 检索 → 引用；知识库/文档级 ACL；VectorStore SPI（Simple/pgvector/Qdrant） |
| 🔌 数据源直连 | MySQL/PostgreSQL 注册、Schema 采集、NL2SQL、只读 SQL 沙箱 |
| 🛡️ 安全沙箱执行 | Python/JS/Shell 代码执行；Process 沙箱默认 + Docker 强隔离 SPI |
| 🧠 Redis 记忆引擎 | 工作记忆 / 会话摘要 / 长期语义记忆 / 用户画像 四层 |
| 🪙 Token 全链路审计 | 流式/非流式 usage 采集、成本、配额、日聚合、看板 |
| 🔐 完整 RBAC | 用户/角色/权限/菜单/部门数据权限；邮箱验证码注册登录，预留短信登录 |
| 🧩 Harness | Agent 定义版本化、Run 生命周期、Hooks、人工审批（HITL）、MCP、评测 |

## 技术栈

- **后端**：Java 21 · Spring Boot 3.5.16 · Spring AI Alibaba 1.1.2.4 · Spring AI 1.1.2 · Spring Security 6 · JPA · Flyway · MySQL 8 / H2 · Redis
- **前端**：Vue 3.5 · TypeScript · Vite · Element Plus · Pinia · ECharts
- **工程**：Maven 多模块 · pnpm · GitHub Actions · Apache-2.0

## 快速开始

> 需要 JDK 21、Maven 3.9+、Node 20+、pnpm 10+；Redis 可选（无 Redis 时记忆引擎自动降级为内存实现）。

```bash
# 1. 后端（默认 dev profile：H2 + Mock 模型，零外部依赖）
mvn -DskipTests package
java -jar agentforge-app/target/agentforge-app-0.1.0-SNAPSHOT.jar

# 2. 前端
cd agentforge-ui && pnpm install && pnpm dev
```

- 控制台：http://localhost:5173
- Swagger：http://localhost:8080/swagger-ui.html
- 健康检查：http://localhost:8080/actuator/health

接入真实模型（DashScope）：

```bash
export AI_DASHSCOPE_API_KEY=sk-xxx
export SPRING_PROFILES_ACTIVE=dev,dashscope
```

## 模块结构

```
agentforge-parent
├── agentforge-common      统一响应/异常/上下文/加密/JPA 基类
├── agentforge-security    账号、认证、邮箱验证、JWT、RBAC、数据权限
├── agentforge-audit       Token 计量、成本、配额、审计日志
├── agentforge-memory      Redis 分层记忆引擎 + 会话 checkpointer
├── agentforge-rag         知识库、文档流水线、向量检索、引用
├── agentforge-datasource  数据源注册、Schema、SQL 沙箱、代码沙箱
├── agentforge-agent       Harness 运行时、工具中心、多智能体编排、MCP
├── agentforge-app         Spring Boot 启动与装配、REST/SSE、Flyway
└── agentforge-ui          Vue3 控制台
```

## 文档

- [技术方案（架构/选型/数据模型/API 规范）](docs/01-技术方案.md)
- [开发路线图（阶段任务/验收标准）](docs/02-开发路线图.md)
- [本地开发环境](docs/03-本地开发环境.md)
- [API 契约](docs/04-API契约.md)

## 参与贡献

见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。欢迎 Issue / PR / Star ⭐

## License

[Apache License 2.0](LICENSE)
