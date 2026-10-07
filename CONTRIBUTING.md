# 参与贡献

路线图见 [#10](https://github.com/NihilDigit/piko/issues/10)。小的 Bug 修复、崩溃排查与文档补充可以直接提交 PR；
计划新增功能或调整架构时，请先提交 Issue，说明使用场景与拟定方案，确认方向后再实现。

## 许可与 CLA

Piko 以 [AGPL-3.0-or-later](LICENSE) 开源。提交 PR 前须签署[个人贡献者协议](CLA.md)（CLA）：贡献者保留版权，
并授权本项目发布所贡献的内容。本项目可另以其他许可证授权，但所贡献的内容始终以 AGPL-3.0-or-later 提供。

首次提交 PR 时，机器人会在 PR 下留言。回复以下内容即完成签署，每位贡献者只需签署一次：

```
I have read the CLA Document and I hereby sign the CLA
```

PR 中每一个提交的作者都须签署。签名记录保存在本仓库的 `cla-signatures` 分支。

## 开发

构建与测试命令见 [CLAUDE.md](CLAUDE.md) 的「常用命令」。改动须在 Android 与桌面端均通过编译。
使用 LLM 辅助编写代码时，请理解新增代码的逻辑，并在真机上验证。
