const { app, BrowserWindow, ipcMain, dialog, Menu } = require('electron');
const path = require('path');
const fs = require('fs');

let mainWindow;
let currentFilePath = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 800,
    minHeight: 600,
    icon: path.join(__dirname, 'icon.ico'),
    backgroundColor: '#00000000',
    transparent: true,
    frame: false,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
      enableRemoteModule: true,
      webSecurity: true
    }
  });

  mainWindow.loadFile('index.html');

  // 개발자 도구
  // mainWindow.webContents.openDevTools();

  // 파일 열기 인자 처리
  if (process.argv.length >= 2) {
    const filePath = process.argv[process.argv.length - 1];
    if (filePath.endsWith('.md') && fs.existsSync(filePath)) {
      currentFilePath = filePath;
      setTimeout(() => {
        loadFile(filePath);
      }, 1000);
    }
  }
}

app.whenReady().then(() => {
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

// 파일 열기
ipcMain.handle('open-file', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openFile'],
    filters: [
      { name: 'Markdown Files', extensions: ['md', 'markdown'] },
      { name: 'All Files', extensions: ['*'] }
    ]
  });

  if (!result.canceled && result.filePaths.length > 0) {
    currentFilePath = result.filePaths[0];
    return loadFile(currentFilePath);
  }
  return null;
});

// 파일 저장
ipcMain.handle('save-file', async (event, content) => {
  if (currentFilePath) {
    fs.writeFileSync(currentFilePath, content, 'utf8');
    return { success: true, path: currentFilePath };
  } else {
    return saveFileAs(content);
  }
});

// 다른 이름으로 저장
ipcMain.handle('save-file-as', async (event, content) => {
  return saveFileAs(content);
});

async function saveFileAs(content) {
  const result = await dialog.showSaveDialog(mainWindow, {
    filters: [
      { name: 'Markdown Files', extensions: ['md'] },
      { name: 'All Files', extensions: ['*'] }
    ]
  });

  if (!result.canceled && result.filePath) {
    currentFilePath = result.filePath;
    fs.writeFileSync(currentFilePath, content, 'utf8');
    return { success: true, path: currentFilePath };
  }
  return { success: false };
}

function loadFile(filePath) {
  try {
    const content = fs.readFileSync(filePath, 'utf8');
    const dir = path.dirname(filePath);
    mainWindow.webContents.send('file-loaded', {
      content,
      path: filePath,
      directory: dir
    });
    return { content, path: filePath, directory: dir };
  } catch (error) {
    console.error('Error loading file:', error);
    return null;
  }
}

// 이미지 경로 처리
ipcMain.handle('resolve-image-path', (event, imagePath, baseDir) => {
  if (imagePath.startsWith('http://') || imagePath.startsWith('https://')) {
    return imagePath;
  }
  
  const fullPath = path.isAbsolute(imagePath) 
    ? imagePath 
    : path.join(baseDir, imagePath);
  
  if (fs.existsSync(fullPath)) {
    return 'file://' + fullPath.replace(/\\/g, '/');
  }
  return imagePath;
});

// 창 제어
ipcMain.on('window-minimize', () => {
  mainWindow.minimize();
});

ipcMain.on('window-maximize', () => {
  if (mainWindow.isMaximized()) {
    mainWindow.unmaximize();
  } else {
    mainWindow.maximize();
  }
});

ipcMain.on('window-close', () => {
  mainWindow.close();
});

// 파일 연결 설정 (Windows)
if (process.platform === 'win32') {
  app.setAsDefaultProtocolClient('markdown-editor');
}