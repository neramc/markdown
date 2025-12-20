const { ipcRenderer } = require('electron');
const fs = require('fs');
const path = require('path');

// 설정
let settings = {
  allowScripts: true,
  autoComplete: true,
  lineNumbers: true,
  lineWrapping: true,
  theme: 'light'
};

// 상태
let currentMode = 'viewer';
let currentFilePath = null;
let currentDirectory = null;
let editor = null;
let isDirty = false;

// 로컬 스토리지에서 설정 로드
function loadSettings() {
  const saved = localStorage.getItem('markdown-editor-settings');
  if (saved) {
    settings = { ...settings, ...JSON.parse(saved) };
  }
  applySettings();
}

// 설정 저장
function saveSettings() {
  localStorage.setItem('markdown-editor-settings', JSON.stringify(settings));
}

// 설정 적용
function applySettings() {
  document.getElementById('allowScripts').checked = settings.allowScripts;
  document.getElementById('autoComplete').checked = settings.autoComplete;
  document.getElementById('lineNumbers').checked = settings.lineNumbers;
  document.getElementById('lineWrapping').checked = settings.lineWrapping;
  
  if (settings.theme === 'dark') {
    document.getElementById('app').classList.add('dark-mode');
    document.getElementById('hljs-light').disabled = true;
    document.getElementById('hljs-dark').disabled = false;
  }
  
  if (editor) {
    editor.setOption('lineNumbers', settings.lineNumbers);
    editor.setOption('lineWrapping', settings.lineWrapping);
  }
}

// marked 설정
marked.setOptions({
  breaks: true,
  gfm: true,
  highlight: function(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang }).value;
      } catch (err) {}
    }
    return hljs.highlightAuto(code).value;
  }
});

// CodeMirror 자동완성 힌트
CodeMirror.commands.autocomplete = function(cm) {
  if (!settings.autoComplete) return;
  
  const cursor = cm.getCursor();
  const token = cm.getTokenAt(cursor);
  const line = cm.getLine(cursor.line);
  
  const hints = [];
  
  // 마크다운 문법 힌트
  const suggestions = [
    { text: '# ', displayText: '# 제목 1', from: cursor },
    { text: '## ', displayText: '## 제목 2', from: cursor },
    { text: '### ', displayText: '### 제목 3', from: cursor },
    { text: '#### ', displayText: '#### 제목 4', from: cursor },
    { text: '##### ', displayText: '##### 제목 5', from: cursor },
    { text: '###### ', displayText: '###### 제목 6', from: cursor },
    { text: '**텍스트**', displayText: '**굵게**', from: cursor },
    { text: '*텍스트*', displayText: '*기울임*', from: cursor },
    { text: '~~텍스트~~', displayText: '~~취소선~~', from: cursor },
    { text: '`코드`', displayText: '`인라인 코드`', from: cursor },
    { text: '```\n\n```', displayText: '```코드 블록```', from: cursor },
    { text: '[링크](url)', displayText: '[링크](url)', from: cursor },
    { text: '![이미지](경로)', displayText: '![이미지](경로)', from: cursor },
    { text: '- ', displayText: '- 목록', from: cursor },
    { text: '1. ', displayText: '1. 번호 목록', from: cursor },
    { text: '- [ ] ', displayText: '- [ ] 체크박스', from: cursor },
    { text: '> ', displayText: '> 인용구', from: cursor },
    { text: '---', displayText: '--- 구분선', from: cursor },
    { text: '| 열1 | 열2 |\n|-----|-----|\n| 값1 | 값2 |', displayText: '| 표 |', from: cursor }
  ];
  
  suggestions.forEach(s => {
    hints.push({
      text: s.text,
      displayText: s.displayText,
      render: function(element, self, data) {
        element.innerHTML = data.displayText;
      }
    });
  });
  
  return {
    list: hints,
    from: CodeMirror.Pos(cursor.line, token.start),
    to: CodeMirror.Pos(cursor.line, token.end)
  };
};

// CodeMirror 초기화
function initEditor() {
  const textarea = document.getElementById('markdownEditor');
  editor = CodeMirror.fromTextArea(textarea, {
    mode: 'markdown',
    theme: settings.theme === 'dark' ? 'dracula' : 'default',
    lineNumbers: settings.lineNumbers,
    lineWrapping: settings.lineWrapping,
    autofocus: true,
    extraKeys: {
      'Ctrl-Space': 'autocomplete',
      'Ctrl-S': function() {
        saveFile();
      }
    }
  });
  
  editor.on('change', () => {
    isDirty = true;
    if (currentMode === 'viewer') {
      renderMarkdown(editor.getValue());
    }
  });
  
  // 자동완성 트리거
  editor.on('inputRead', function(cm, change) {
    if (!settings.autoComplete) return;
    if (change.text[0] && /[\w#*`\-\[\]>|]/.test(change.text[0])) {
      CodeMirror.commands.autocomplete(cm);
    }
  });
}

// 마크다운 렌더링
function renderMarkdown(markdown) {
  const preview = document.getElementById('markdownPreview');
  
  if (!markdown.trim()) {
    preview.innerHTML = '<p style="text-align: center; opacity: 0.5; margin-top: 50px;">마크다운 내용을 입력하세요...</p>';
    return;
  }
  
  // 이미지 경로 처리
  let processedMarkdown = markdown;
  if (currentDirectory) {
    processedMarkdown = markdown.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, (match, alt, imagePath) => {
      if (imagePath.startsWith('http://') || imagePath.startsWith('https://')) {
        return match;
      }
      const fullPath = path.isAbsolute(imagePath) 
        ? imagePath 
        : path.join(currentDirectory, imagePath);
      
      if (fs.existsSync(fullPath)) {
        const fileUrl = 'file://' + fullPath.replace(/\\/g, '/');
        return `![${alt}](${fileUrl})`;
      }
      return match;
    });
    
    // HTML 이미지 태그도 처리
    processedMarkdown = processedMarkdown.replace(/<img\s+[^>]*src="([^"]+)"[^>]*>/gi, (match, imagePath) => {
      if (imagePath.startsWith('http://') || imagePath.startsWith('https://') || imagePath.startsWith('data:')) {
        return match;
      }
      const fullPath = path.isAbsolute(imagePath) 
        ? imagePath 
        : path.join(currentDirectory, imagePath);
      
      if (fs.existsSync(fullPath)) {
        const fileUrl = 'file://' + fullPath.replace(/\\/g, '/');
        return match.replace(imagePath, fileUrl);
      }
      return match;
    });
  }
  
  // 마크다운을 HTML로 변환
  let html = marked.parse(processedMarkdown);
  
  // 스크립트 처리
  if (!settings.allowScripts) {
    html = html.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, 
      '<pre style="background: #fff3cd; padding: 10px; border-left: 4px solid #ffc107;"><code>[스크립트가 비활성화되었습니다]</code></pre>');
  }
  
  preview.innerHTML = html;
  
  // 스크립트 실행 (허용된 경우)
  if (settings.allowScripts) {
    const scripts = preview.querySelectorAll('script');
    scripts.forEach(script => {
      const newScript = document.createElement('script');
      if (script.src) {
        newScript.src = script.src;
      } else {
        newScript.textContent = script.textContent;
      }
      script.parentNode.replaceChild(newScript, script);
    });
  }
  
  // 외부 링크는 기본 브라우저에서 열기
  const links = preview.querySelectorAll('a');
  links.forEach(link => {
    link.addEventListener('click', (e) => {
      if (link.href.startsWith('http://') || link.href.startsWith('https://')) {
        e.preventDefault();
        require('electron').shell.openExternal(link.href);
      }
    });
  });
}

// 파일 열기
async function openFile() {
  const result = await ipcRenderer.invoke('open-file');
  if (result) {
    currentFilePath = result.path;
    currentDirectory = result.directory;
    editor.setValue(result.content);
    document.getElementById('filePath').textContent = path.basename(result.path);
    renderMarkdown(result.content);
    isDirty = false;
  }
}

// 파일 저장
async function saveFile() {
  const content = editor.getValue();
  const result = await ipcRenderer.invoke('save-file', content);
  if (result.success) {
    isDirty = false;
    if (result.path !== currentFilePath) {
      currentFilePath = result.path;
      currentDirectory = path.dirname(result.path);
      document.getElementById('filePath').textContent = path.basename(result.path);
    }
  }
}

// 다른 이름으로 저장
async function saveFileAs() {
  const content = editor.getValue();
  const result = await ipcRenderer.invoke('save-file-as', content);
  if (result.success) {
    isDirty = false;
    currentFilePath = result.path;
    currentDirectory = path.dirname(result.path);
    document.getElementById('filePath').textContent = path.basename(result.path);
  }
}

// 모드 전환
function switchMode(mode) {
  currentMode = mode;
  const viewerContainer = document.getElementById('viewerContainer');
  const editorContainer = document.getElementById('editorContainer');
  const viewerBtn = document.getElementById('viewerBtn');
  const editorBtn = document.getElementById('editorBtn');
  
  if (mode === 'viewer') {
    viewerContainer.classList.remove('hidden');
    editorContainer.classList.add('hidden');
    viewerBtn.classList.add('active');
    editorBtn.classList.remove('active');
    renderMarkdown(editor.getValue());
  } else {
    viewerContainer.classList.add('hidden');
    editorContainer.classList.remove('hidden');
    viewerBtn.classList.remove('active');
    editorBtn.classList.add('active');
    editor.refresh();
  }
}

// 테마 전환
function toggleTheme() {
  const app = document.getElementById('app');
  const isDark = app.classList.contains('dark-mode');
  
  if (isDark) {
    app.classList.remove('dark-mode');
    document.getElementById('hljs-light').disabled = false;
    document.getElementById('hljs-dark').disabled = true;
    settings.theme = 'light';
    if (editor) editor.setOption('theme', 'default');
  } else {
    app.classList.add('dark-mode');
    document.getElementById('hljs-light').disabled = true;
    document.getElementById('hljs-dark').disabled = false;
    settings.theme = 'dark';
    if (editor) editor.setOption('theme', 'dracula');
  }
  
  saveSettings();
  renderMarkdown(editor.getValue());
}

// 설정 모달
function openSettings() {
  document.getElementById('settingsModal').classList.remove('hidden');
}

function closeSettings() {
  document.getElementById('settingsModal').classList.add('hidden');
}

function saveSettingsFromModal() {
  settings.allowScripts = document.getElementById('allowScripts').checked;
  settings.autoComplete = document.getElementById('autoComplete').checked;
  settings.lineNumbers = document.getElementById('lineNumbers').checked;
  settings.lineWrapping = document.getElementById('lineWrapping').checked;
  
  applySettings();
  saveSettings();
  closeSettings();
  renderMarkdown(editor.getValue());
}

// 이벤트 리스너
document.addEventListener('DOMContentLoaded', () => {
  loadSettings();
  initEditor();
  renderMarkdown('');
  
  // Toolbar 버튼
  document.getElementById('openBtn').addEventListener('click', openFile);
  document.getElementById('saveBtn').addEventListener('click', saveFile);
  document.getElementById('saveAsBtn').addEventListener('click', saveFileAs);
  document.getElementById('viewerBtn').addEventListener('click', () => switchMode('viewer'));
  document.getElementById('editorBtn').addEventListener('click', () => switchMode('editor'));
  document.getElementById('themeBtn').addEventListener('click', toggleTheme);
  document.getElementById('settingsBtn').addEventListener('click', openSettings);
  
  // Settings Modal
  document.getElementById('closeSettingsBtn').addEventListener('click', closeSettings);
  document.getElementById('saveSettingsBtn').addEventListener('click', saveSettingsFromModal);
  document.getElementById('settingsModal').addEventListener('click', (e) => {
    if (e.target.id === 'settingsModal') closeSettings();
  });
  
  // Title Bar 버튼
  document.getElementById('minimizeBtn').addEventListener('click', () => {
    ipcRenderer.send('window-minimize');
  });
  
  document.getElementById('maximizeBtn').addEventListener('click', () => {
    ipcRenderer.send('window-maximize');
  });
  
  document.getElementById('closeBtn').addEventListener('click', () => {
    if (isDirty) {
      if (confirm('저장하지 않은 변경사항이 있습니다. 정말 닫으시겠습니까?')) {
        ipcRenderer.send('window-close');
      }
    } else {
      ipcRenderer.send('window-close');
    }
  });
  
  // 키보드 단축키
  document.addEventListener('keydown', (e) => {
    if (e.ctrlKey) {
      if (e.key === 'o') {
        e.preventDefault();
        openFile();
      } else if (e.key === 's') {
        e.preventDefault();
        if (e.shiftKey) {
          saveFileAs();
        } else {
          saveFile();
        }
      }
    }
  });
});

// IPC 이벤트 리스너
ipcRenderer.on('file-loaded', (event, data) => {
  currentFilePath = data.path;
  currentDirectory = data.directory;
  editor.setValue(data.content);
  document.getElementById('filePath').textContent = path.basename(data.path);
  renderMarkdown(data.content);
  isDirty = false;
});