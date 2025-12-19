<div align="center">

# 📝 Markdown Editor & Viewer

<img src="icon.ico" alt="Markdown Editor Logo" width="128" height="128">

### ✨ Beautiful Windows Markdown Editor with WinUI 3 Style ✨

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE.txt)
[![Electron](https://img.shields.io/badge/Electron-28.0+-9FEAF9?logo=electron&logoColor=white)](https://www.electronjs.org/)
[![Windows](https://img.shields.io/badge/Windows-10%2B-0078D6?logo=windows&logoColor=white)](https://www.microsoft.com/windows)
[![CodeMirror](https://img.shields.io/badge/CodeMirror-5.65-D30707?logo=codemirror&logoColor=white)](https://codemirror.net/)
[![Inno Setup](https://img.shields.io/badge/Inno%20Setup-6.0+-264F73)](https://jrsoftware.org/isinfo.php)

[![GitHub Stars](https://img.shields.io/github/stars/yourusername/markdown-editor?style=social)](https://github.com/yourusername/markdown-editor)
[![GitHub Forks](https://img.shields.io/github/forks/yourusername/markdown-editor?style=social)](https://github.com/yourusername/markdown-editor)
[![GitHub Issues](https://img.shields.io/github/issues/yourusername/markdown-editor)](https://github.com/yourusername/markdown-editor/issues)

[🚀 Download](#-download) • [📖 Features](#-features) • [🎨 Screenshots](#-screenshots) • [⚙️ Installation](#️-installation) • [📚 Documentation](#-documentation)

---

**A modern, feature-rich Markdown editor and viewer for Windows with stunning WinUI 3 acrylic effects, real-time preview, and comprehensive Markdown support.**

[English](#) | [한국어](#)

</div>

---

## 🌟 Highlights

<table>
<tr>
<td width="50%">

### 🎨 **Beautiful UI/UX**
- **WinUI 3 Style Design** with translucent acrylic effects
- **Light & Dark Mode** support with smooth transitions
- **Custom Title Bar** with modern window controls
- **Responsive Layout** optimized for all screen sizes

</td>
<td width="50%">

### ⚡ **Powerful Features**
- **Real-time Preview** with instant markdown rendering
- **Smart Auto-complete** for markdown syntax
- **Syntax Highlighting** powered by Highlight.js
- **Image Support** with relative/absolute paths

</td>
</tr>
</table>

---

## ✨ Features

### 📝 Editor

<details open>
<summary><b>Click to expand</b></summary>

- 🎯 **Dual Mode**: Seamlessly switch between Viewer and Editor modes
- ⚡ **Live Preview**: See your changes in real-time
- 🔮 **Auto-completion**: Intelligent markdown syntax suggestions
- 🎨 **Syntax Highlighting**: Beautiful code blocks with 190+ languages support
- 📏 **Line Numbers**: Toggle line numbers on/off
- 🔄 **Auto Line Wrap**: Comfortable reading experience
- 💾 **Auto-save**: Never lose your work
- ⌨️ **Keyboard Shortcuts**: Boost your productivity

</details>

### 📋 Markdown Support

<details open>
<summary><b>Full Markdown Specification</b></summary>

#### ✅ Supported Syntax

| Category | Elements |
|----------|----------|
| **Headings** | H1 ~ H6 |
| **Emphasis** | Bold, Italic, Strikethrough |
| **Code** | Inline code, Code blocks with syntax highlighting |
| **Links** | URLs, Reference links, Auto-links |
| **Images** | Inline images, Reference images, Image URLs |
| **Lists** | Ordered lists, Unordered lists, Nested lists |
| **Tasks** | Task lists with checkboxes |
| **Quotes** | Blockquotes with nesting |
| **Tables** | GitHub Flavored Markdown tables |
| **HTML** | Inline HTML tags |
| **Scripts** | Optional JavaScript execution |
| **Horizontal Rules** | Dividers |

</details>

### 🖼️ Image Handling

- 📁 **Relative Path**: `![alt](./images/photo.jpg)`
- 🌐 **Absolute Path**: `![alt](C:/Users/photo.jpg)`
- 🔗 **URL Images**: `![alt](https://example.com/image.png)`
- 🏷️ **HTML Images**: `<img src="path" alt="description">`

### ⚙️ Settings

<table>
<tr>
<td align="center">🔒</td>
<td><b>Script Execution Control</b><br/>Enable/disable JavaScript in markdown</td>
</tr>
<tr>
<td align="center">🔮</td>
<td><b>Auto-complete Toggle</b><br/>Turn smart suggestions on/off</td>
</tr>
<tr>
<td align="center">📏</td>
<td><b>Line Numbers</b><br/>Show/hide line numbers</td>
</tr>
<tr>
<td align="center">🔄</td>
<td><b>Line Wrapping</b><br/>Enable automatic line wrapping</td>
</tr>
<tr>
<td align="center">🌓</td>
<td><b>Theme Switching</b><br/>Toggle between light and dark modes</td>
</tr>
</table>

---

## 🎨 Screenshots

<div align="center">

### Light Mode - Viewer
![Light Mode Viewer](https://via.placeholder.com/800x500/F3F3F3/333333?text=Light+Mode+Viewer)

### Dark Mode - Editor
![Dark Mode Editor](https://via.placeholder.com/800x500/202020/E0E0E0?text=Dark+Mode+Editor)

### Settings Panel
![Settings](https://via.placeholder.com/400x300/FFFFFF/000000?text=Settings+Panel)

</div>

---

## 🚀 Download

### Latest Release

<div align="center">

[![Download](https://img.shields.io/badge/Download-Latest%20Release-success?style=for-the-badge&logo=windows)](https://github.com/yourusername/markdown-editor/releases/latest)

**Version 1.0.0** • Released: 2024-12-20

</div>

### System Requirements

- 🪟 **OS**: Windows 10 or later (64-bit)
- 💻 **RAM**: 4 GB minimum, 8 GB recommended
- 💾 **Storage**: 200 MB free space
- 🖥️ **Display**: 1280x720 or higher resolution

---

## ⚙️ Installation

### 📦 For End Users

1. **Download** the installer: `MarkdownEditor-Setup-1.0.0.exe`
2. **Run** the installer with administrator privileges
3. **Choose** installation directory
4. **Select** optional features:
   - ✅ Create desktop shortcut
   - ✅ Associate `.md` files
5. **Launch** the application

### 🛠️ For Developers

<details>
<summary><b>Building from Source</b></summary>

#### Prerequisites

```bash
# Required
Node.js >= 16.0.0
npm >= 8.0.0

# Optional (for building installer)
Inno Setup >= 6.0.0
ImageMagick (for image conversion)
```

#### Clone Repository

```bash
git clone https://github.com/yourusername/markdown-editor.git
cd markdown-editor
```

#### Install Dependencies

```bash
npm install
```

#### Development Mode

```bash
npm start
```

#### Build Application

```bash
# Build Electron app
npm run build

# Output: dist/win-unpacked/
```

#### Create Installer

##### Option 1: Automatic Build Script (Recommended)

```bash
# Windows Batch Script
build-innosetup.bat
```

##### Option 2: Manual Build

```bash
# 1. Install Inno Setup
choco install innosetup

# 2. Build Electron app
npm run build

# 3. Compile installer
"C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer.iss
```

#### Output

```
dist/
├── win-unpacked/                          # Portable app
└── MarkdownEditor-Setup-1.0.0.exe         # Installer
```

</details>

---

## 📁 Project Structure

```
markdown-editor/
│
├── 📄 main.js                   # Electron main process
├── 🌐 index.html                # Application UI structure
├── 🎨 styles.css                # WinUI 3 styling
├── ⚙️ renderer.js               # Renderer process logic
│
├── 📦 package.json              # Project configuration
├── 🔧 installer.iss             # Inno Setup script
├── 🚀 build-innosetup.bat       # Automated build script
│
├── 🖼️ icon.ico                  # Application icon
├── 🖼️ installer-header.bmp      # Installer header (55x58)
├── 🖼️ installer-sidebar.bmp     # Installer sidebar (164x314)
│
├── 📜 LICENSE.txt               # Apache 2.0 license
└── 📖 README.md                 # This file
```

---

## 🎯 Usage

### ⌨️ Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl + O` | Open file |
| `Ctrl + S` | Save file |
| `Ctrl + Shift + S` | Save as |
| `Ctrl + Space` | Trigger auto-complete |
| `F11` | Toggle fullscreen |

### 🔄 Mode Switching

- **👁️ Viewer Mode**: Read and preview rendered markdown
- **✏️ Editor Mode**: Write and edit markdown with live preview

### 📝 Markdown Examples

<details>
<summary><b>Basic Syntax Examples</b></summary>

#### Headings
```markdown
# H1 Heading
## H2 Heading
### H3 Heading
```

#### Emphasis
```markdown
**Bold Text**
*Italic Text*
~~Strikethrough~~
`Inline Code`
```

#### Links & Images
```markdown
[Link Text](https://example.com)
![Image Description](./images/photo.jpg)
```

#### Lists
```markdown
- Unordered item 1
- Unordered item 2
  - Nested item

1. Ordered item 1
2. Ordered item 2
```

#### Task Lists
```markdown
- [ ] Unchecked task
- [x] Completed task
```

#### Code Blocks
````markdown
```javascript
function hello() {
  console.log("Hello, World!");
}
```
````

#### Tables
```markdown
| Column 1 | Column 2 | Column 3 |
|----------|----------|----------|
| Value 1  | Value 2  | Value 3  |
```

#### Blockquotes
```markdown
> This is a blockquote
> It can span multiple lines
```

</details>

---

## 🛠️ Technology Stack

<div align="center">

| Technology | Purpose | Version |
|------------|---------|---------|
| ![Electron](https://img.shields.io/badge/Electron-47848F?style=flat&logo=electron&logoColor=white) | Desktop framework | 28.0+ |
| ![Node.js](https://img.shields.io/badge/Node.js-339933?style=flat&logo=node.js&logoColor=white) | Runtime environment | 16.0+ |
| ![JavaScript](https://img.shields.io/badge/JavaScript-F7DF1E?style=flat&logo=javascript&logoColor=black) | Programming language | ES6+ |
| ![CodeMirror](https://img.shields.io/badge/CodeMirror-D30707?style=flat&logo=codemirror&logoColor=white) | Code editor | 5.65 |
| ![Marked](https://img.shields.io/badge/Marked-000000?style=flat&logo=markdown&logoColor=white) | Markdown parser | 11.0+ |
| ![Highlight.js](https://img.shields.io/badge/Highlight.js-23272A?style=flat) | Syntax highlighting | 11.9+ |
| ![Inno Setup](https://img.shields.io/badge/Inno%20Setup-264F73?style=flat) | Installer builder | 6.0+ |

</div>

---

## 🎨 Customization

### 🖼️ Installer Images

For Inno Setup installer, prepare these images:

#### Header Image (Small Logo)
- **Size**: 55 × 58 pixels
- **Format**: BMP
- **Location**: Top-right corner of installer

#### Sidebar Image (Vertical Banner)
- **Size**: 164 × 314 pixels
- **Format**: BMP
- **Location**: Left side of installer

#### Converting Images

```bash
# Install ImageMagick
choco install imagemagick

# Convert header (55x58)
magick convert your-banner.png -resize 55x58! installer-header.bmp

# Convert sidebar (164x314)
magick convert your-banner.png -resize 164x314! installer-sidebar.bmp
```

### 🎨 Theming

The application supports custom themes. Edit `styles.css` to customize:

- Colors
- Font sizes
- Spacing
- Acrylic effects
- Window chrome

---

## 🤝 Contributing

Contributions are welcome! Here's how you can help:

<div align="center">

[![Contribute](https://img.shields.io/badge/Contribute-Guide-brightgreen?style=for-the-badge)](CONTRIBUTING.md)

</div>

### 🐛 Reporting Bugs

Found a bug? Please [open an issue](https://github.com/yourusername/markdown-editor/issues/new?template=bug_report.md) with:
- Description of the bug
- Steps to reproduce
- Expected behavior
- Screenshots (if applicable)
- System information

### 💡 Feature Requests

Have an idea? [Submit a feature request](https://github.com/yourusername/markdown-editor/issues/new?template=feature_request.md)!

### 🔧 Pull Requests

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📋 Roadmap

- [x] Basic markdown editing and preview
- [x] Light/Dark mode support
- [x] Auto-completion
- [x] File association
- [ ] Plugin system
- [ ] Export to PDF/HTML
- [ ] Cloud sync support
- [ ] Multi-language UI
- [ ] Vim/Emacs keybindings
- [ ] Git integration
- [ ] Collaborative editing

---

## ❓ FAQ

<details>
<summary><b>How do I associate .md files with this editor?</b></summary>

During installation, check the "Associate .md files" option. If you missed it, right-click any `.md` file → "Open with" → "Choose another app" → Select Markdown Editor → Check "Always use this app".

</details>

<details>
<summary><b>Can I use custom fonts?</b></summary>

Yes! Edit the `font-family` property in `styles.css` and rebuild the application.

</details>

<details>
<summary><b>Is script execution safe?</b></summary>

Script execution is disabled by default. Enable it only for trusted markdown files. The app sandboxes scripts but cannot guarantee complete security.

</details>

<details>
<summary><b>How do I report security vulnerabilities?</b></summary>

Please email security concerns to security@yourproject.com instead of opening public issues.

</details>

---

## 📄 License

This project is licensed under the **Apache License 2.0** - see the [LICENSE.txt](LICENSE.txt) file for details.

```
Copyright 2024 Markdown Editor Team

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## 🙏 Acknowledgments

- **[Electron](https://www.electronjs.org/)** - Cross-platform desktop apps
- **[CodeMirror](https://codemirror.net/)** - Versatile text editor
- **[Marked](https://marked.js.org/)** - Markdown parser and compiler
- **[Highlight.js](https://highlightjs.org/)** - Syntax highlighting
- **[Inno Setup](https://jrsoftware.org/isinfo.php)** - Installer for Windows
- **Microsoft** - WinUI 3 design inspiration

---

## 🌐 Links

<div align="center">

[![Website](https://img.shields.io/badge/Website-Visit-blue?style=for-the-badge)](https://yourwebsite.com)
[![Documentation](https://img.shields.io/badge/Docs-Read-green?style=for-the-badge)](https://docs.yourwebsite.com)
[![Discord](https://img.shields.io/badge/Discord-Join-7289DA?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/yourserver)
[![Twitter](https://img.shields.io/badge/Twitter-Follow-1DA1F2?style=for-the-badge&logo=twitter&logoColor=white)](https://twitter.com/yourhandle)

</div>

---

## 📊 Statistics

<div align="center">

![Alt](https://repobeats.axiom.co/api/embed/yourhash.svg "Repobeats analytics image")

[![GitHub Activity](https://img.shields.io/github/commit-activity/m/yourusername/markdown-editor)](https://github.com/yourusername/markdown-editor/commits)
[![GitHub Contributors](https://img.shields.io/github/contributors/yourusername/markdown-editor)](https://github.com/yourusername/markdown-editor/graphs/contributors)
[![GitHub Last Commit](https://img.shields.io/github/last-commit/yourusername/markdown-editor)](https://github.com/yourusername/markdown-editor/commits)

</div>

---

## 💖 Support

If you find this project helpful, please consider:

<div align="center">

⭐ **Star this repository**  
🔀 **Fork and contribute**  
🐛 **Report issues**  
💬 **Spread the word**

[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20A%20Coffee-Donate-FFDD00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://www.buymeacoffee.com/yourhandle)
[![PayPal](https://img.shields.io/badge/PayPal-Donate-00457C?style=for-the-badge&logo=paypal&logoColor=white)](https://paypal.me/yourhandle)

</div>

---

<div align="center">

### Made with ❤️ by [Your Name](https://github.com/yourusername)

**⭐ Star us on GitHub — it helps!**

[🔝 Back to Top](#-markdown-editor--viewer)

</div>
