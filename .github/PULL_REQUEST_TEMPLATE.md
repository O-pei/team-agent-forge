## 变更说明

<!-- 这个 PR 做了什么，为什么需要 --> 

## 关联 Issue

<!-- Closes #123 -->

## 类型

- [ ] feat：新功能
- [ ] fix：缺陷修复
- [ ] docs：文档
- [ ] test：测试
- [ ] chore：构建/依赖/杂项
- [ ] BREAKING CHANGE：破坏 API 契约或数据库迁移

## 自查清单

- [ ] `mvn -B verify` 通过（后端单测 + 集成测试）
- [ ] `cd agentforge-ui && pnpm build` 通过（改了前端时）
- [ ] 新增/修改的 Flyway 迁移**没有改动已发布的 V1~V6**，且编号不冲突
- [ ] 接口改动同步更新了 `docs/04-API契约.md`
- [ ] 无明文密钥 / Token / 密码 / 真实 JDBC 连接串
- [ ] 关键路径（认证、SQL 守卫、沙箱、审计）有测试覆盖
- [ ] 本地跑过 `node scripts/smoke-test.mjs`（改了后端接口时）

## 测试记录

<!-- 命令与关键输出，例如 node scripts/smoke-test.mjs → PASS=31 FAIL=0 -->
