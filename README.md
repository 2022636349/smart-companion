# 智学引擎 · 智慧学伴

面向师生的学情诊断与个性化学习平台，包含学习内容与要求梳理、学生学情分析、学习建议与教学方案。

## 目录

- `smart-companion-platform/`：独立师生前端。
- `business-service/`：Spring Boot 业务与智慧学伴 API；包含启动所需的原项目共享模块。
- `knowledge-service/`：知识内容与图谱服务。
- `ai-service/`：模型接入、学习建议及相关共享服务。
- `scripts/smart_companion/`：本机启动和停止脚本。

浏览器调用 Spring Boot `/api/**`；模型服务根据已审核内容和学情统计生成建议，教师确认后学生查看任务。

## 运行说明

`启动智学引擎.cmd` 和配套脚本依赖原开发机 D 盘既有的 MySQL、Java、Maven、Node、Python 环境及初始化数据；克隆后不能保证直接运行，需要根据新机器修改路径、配置服务和准备数据。原始赛题资料、教材原件、数据库、账号密码、环境密钥、依赖和构建产物不包含在仓库中。请按赛题资料的授权范围使用和分享内容。

教学方案操作详见 `docs/agent-context/smart-companion/智学引擎_智能教学方案使用说明_20261112.md`。
