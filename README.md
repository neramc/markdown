# Markdown Editor & Viewer

Windows용 Electron 기반 마크다운 에디터 및 뷰어입니다. WinUI 3 스타일의 아크릴 효과와 완전한 마크다운 지원을 제공합니다.

## 주요 기능

### 🎨 UI/UX
- **WinUI 3 스타일**: 반투명 아크릴 효과와 현대적인 디자인
- **라이트/다크 모드**: 테마 전환 지원
- **커스텀 타이틀바**: 드래그 가능한 윈도우 컨트롤

### ✍️ 에디터
- **실시간 프리뷰**: 뷰어/에디터 모드 전환
- **자동완성**: 마크다운 문법 자동완성 기능
- **코드 하이라이팅**: CodeMirror 기반 편집기
- **줄 번호 및 자동 줄바꿈**: 설정에서 제어 가능

### 📝 마크다운 지원
- 제목 (H1-H6)
- 굵게, 기울임, 취소선
- 인라인 코드 및 코드 블록
- 링크 및 이미지
- 목록 (순서/비순서)
- 체크박스
- 인용구
- 표 (Table)
- 구분선
- HTML 태그
- 스크립트 실행 (설정에서 제어)

### 🖼️ 이미지 지원
- 상대 경로 이미지 자동 로드
- 절대 경로 이미지 지원
- URL 이미지 지원
- `<img>` 태그 지원

### ⚙️ 설정
- 스크립트 실행 허용/차단
- 자동완성 기능 토글
- 줄 번호 표시
- 자동 줄바꿈

## 설치 방법

### 1. 필수 프로그램 설치

```bash
npm install
```

### 2. 아이콘 준비

프로젝트 루트에 `icon.ico` 파일을 배치하세요.

### 3. 설치 프로그램용 이미지 준비

NSIS 설치 프로그램을 위해 다음 이미지들이 필요합니다:

- **installer-header.bmp**: 150x57 픽셀 (설치 프로그램 상단 배너)
- **installer-sidebar.bmp**: 164x314 픽셀 (설치 프로그램 사이드바)

1920x1080 이미지를 위 크기로 리사이즈하여 사용하세요.

### 이미지 리사이즈 방법

PowerShell을 사용한 이미지 리사이즈:

```powershell
# ImageMagick 설치 (chocolatey 사용)
choco install imagemagick

# 헤더 이미지 생성 (150x57)
magick convert your-image.png -resize 150x57! installer-header.bmp

# 사이드바 이미지 생성 (164x314)
magick convert your-image.png -resize 164x314! installer-sidebar.bmp
```

또는 온라인 도구를 사용하여 리사이즈할 수 있습니다.

### 4. 라이센스 파일 생성

프로젝트 루트에 `LICENSE.txt` 파일을 생성하세요.

```txt
MIT License

Copyright (c) 2024 Markdown Editor

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software")...
```

## 실행 방법

### 개발 모드

```bash
npm start
```

### 빌드

```bash
npm run build
```

빌드된 파일은 `dist` 폴더에 생성됩니다.

### 설치 프로그램 생성

```bash
npm run dist
```

`MarkdownEditor-Setup.exe` 설치 파일이 생성됩니다.

## 사용 방법

### 파일 작업
- **열기**: 상단 "📂 열기" 버튼 또는 `Ctrl+O`
- **저장**: 상단 "💾 저장" 버튼 또는 `Ctrl+S`
- **다른 이름으로 저장**: 상단 "📥 다른 이름으로" 버튼 또는 `Ctrl+Shift+S`

### 모드 전환
- **뷰어 모드**: 마크다운 렌더링 결과 확인
- **에디터 모드**: 마크다운 편집

### 자동완성
에디터 모드에서 `Ctrl+Space`를 누르거나 마크다운 문법 시작 문자를 입력하면 자동완성이 활성화됩니다.

### 키보드 단축키
- `Ctrl+O`: 파일 열기
- `Ctrl+S`: 파일 저장
- `Ctrl+Shift+S`: 다른 이름으로 저장
- `Ctrl+Space`: 자동완성

## 프로젝트 구조

```
markdown-editor/
├── main.js              # Electron 메인 프로세스
├── index.html           # HTML 구조
├── styles.css           # WinUI 3 스타일
├── renderer.js          # 렌더러 프로세스
├── package.json         # 프로젝트 설정
├── installer.nsi        # NSIS 설치 스크립트
├── icon.ico             # 앱 아이콘
├── installer-header.bmp # 설치 프로그램 헤더
├── installer-sidebar.bmp# 설치 프로그램 사이드바
└── LICENSE.txt          # 라이센스
```

## 기술 스택

- **Electron**: 데스크톱 앱 프레임워크
- **CodeMirror**: 코드 에디터
- **Marked**: 마크다운 파서
- **Highlight.js**: 코드 하이라이팅
- **NSIS**: 설치 프로그램 빌더

## 마크다운 문법 예제

### 제목
```markdown
# H1 제목
## H2 제목
### H3 제목
```

### 강조
```markdown
**굵게**
*기울임*
~~취소선~~
`인라인 코드`
```

### 링크 & 이미지
```markdown
[링크 텍스트](https://example.com)
![이미지 설명](./images/photo.jpg)
```

### 목록
```markdown
- 항목 1
- 항목 2
  - 하위 항목

1. 첫 번째
2. 두 번째
```

### 체크박스
```markdown
- [ ] 할 일 1
- [x] 완료된 일
```

### 표
```markdown
| 열1 | 열2 | 열3 |
|-----|-----|-----|
| 값1 | 값2 | 값3 |
```

### 코드 블록
````markdown
```javascript
function hello() {
  console.log("Hello, World!");
}
```
````

## 주의사항

1. **스크립트 실행**: 보안을 위해 설정에서 스크립트 실행을 제어할 수 있습니다.
2. **이미지 경로**: 상대 경로 이미지는 마크다운 파일과 같은 디렉터리 기준으로 로드됩니다.
3. **파일 연결**: 설치 후 .md 파일을 더블클릭하면 자동으로 이 앱에서 열립니다.

## 라이센스

Apache 2.0 License

## 문의

문제가 발생하거나 기능 제안이 있으시면 이슈를 등록해주세요.