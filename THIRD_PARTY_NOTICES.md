# 第三方组件许可声明（Third-Party Notices）

本项目（PhotoTimeFixer）自身代码采用 [MIT 许可证](LICENSE)。

但 `video_time_fixer/tools/` 目录下打包分发的 **ExifTool（含 Strawberry Perl 运行时）** 是第三方软件，**不适用本项目的 MIT 许可证**，各自遵循其上游许可证如下。

## ExifTool

- **用途**：视频时间脚本的依赖，用于读取/写入文件元数据
- **官网**：https://exiftool.org/
- **许可证**：Artistic License 1.0 或 GNU GPL v1（与 Perl 本身相同的条款，双许可证，任选其一）
- **版权**：© 2003–2026 Phil Harvey
- **说明**：本项目未修改 ExifTool，仅以二进制形式随包分发

## Strawberry Perl（Perl 运行时）

- **用途**：ExifTool Windows 便携版所需运行时
- **官网**：https://strawberryperl.com/
- **许可证**：Artistic License / GPL（Perl 发行版；其中各模块与依赖 C 库的具体许可证见 `tools/exiftool_files/Licenses_Strawberry_Perl.zip`）

## Windows Launcher（打包器）

- **许可证**：CC0 1.0（公有领域）
- **声明**：见 `tools/exiftool_files/readme_windows.txt`

## 完整许可证文本位置

随包分发，位于：

- `video_time_fixer/tools/exiftool_files/LICENSE`
- `video_time_fixer/tools/exiftool_files/Licenses_Strawberry_Perl.zip`

## 源码获取

- ExifTool 源码：https://exiftool.org/
- Strawberry Perl 源码：https://strawberryperl.com/
