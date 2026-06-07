# CLAUDE.md

项目操作手册 —— 在这个 codebase 里工作时,Claude 应遵循的约定与教训。

## 代码搜索:优先用 LSP,不要 Read + grep 凑

`LSP` 工具(`goToDefinition` / `findReferences` / `documentSymbol` / `hover` / `workspaceSymbol`)在本项目可用,且比 `Read` + `Bash` grep 快得多。

| 想知道的 | 用 |
|---|---|
| 类 X 有哪些成员 / 方法签名 | `LSP documentSymbol` |
| 符号 Y 在哪里被引用 | `LSP findReferences` |
| 这个变量 / 方法是什么类型 | `LSP hover` |
| 符号在哪里定义 | `LSP goToDefinition` |
| 全工作区搜符号 | `LSP workspaceSymbol`(索引完成后) |

`Read` + `Bash` grep 仍然保留给:
- 搜**字符串字面量**(错误信息、log format、注释里的关键字)
- 需要**实现细节**的查看(具体逻辑、多行上下文)

**注意**:LSP 第一次调用可能返回 `LSP server has not finished indexing the project` —— 等一下或换个文件,不要立刻退回 grep。
