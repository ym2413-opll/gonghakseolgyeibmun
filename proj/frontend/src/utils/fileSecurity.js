// src/utils/fileSecurity.js
// 질문 첨부 파일에 대한 엄격한 클라이언트단 사전 보안 검사 유틸리티

export const MAX_FILE_SIZE_BYTES = 15 * 1024 * 1024; // 15MB
export const MAX_ATTACHMENTS_COUNT = 5; // 최대 5개 파일

export const ALLOWED_EXTENSIONS = new Set([
  'png', 'jpg', 'jpeg', 'gif', 'webp',
  'pdf', 'docx', 'doc', 'xlsx', 'xls', 'pptx', 'ppt',
  'hwp', 'hwpx', 'txt', 'zip'
]);

export const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp']);

export const DANGEROUS_EXTENSIONS = new Set([
  'exe', 'bat', 'cmd', 'sh', 'vbs', 'js', 'mjs', 'msi',
  'jar', 'py', 'php', 'jsp', 'asp', 'aspx', 'html', 'htm',
  'svg', 'dll', 'com', 'scr', 'lnk', 'ps1', 'wsf', 'reg', 'bin'
]);

/**
 * 파일 확장자 추출
 */
export function getFileExtension(filename) {
  if (!filename || typeof filename !== 'string') return '';
  const idx = filename.lastIndexOf('.');
  return idx >= 0 ? filename.slice(idx + 1).toLowerCase() : '';
}

/**
 * 파일 바이트 헤더(Magic Bytes)를 비동기로 읽어 확장자 위조 여부 검사
 */
export async function validateFileMagicBytes(file, extension) {
  return new Promise((resolve, reject) => {
    // 텍스트 파일의 경우 바이트 시그니처는 건너뜀
    if (extension === 'txt') {
      return resolve(true);
    }

    const reader = new FileReader();
    const slice = file.slice(0, 16);

    reader.onloadend = () => {
      if (!reader.result) {
        return reject(new Error('파일 헤더를 읽을 수 없습니다.'));
      }

      const arr = new Uint8Array(reader.result);
      if (arr.length < 4) {
        return reject(new Error('파일 크기가 너무 작아 유효하지 않습니다.'));
      }

      // 1. 실행 파일 시그니처 (MZ / ELF) 차단
      if (arr[0] === 0x4d && arr[1] === 0x5a) {
        return reject(new Error('실행 파일(EXE/DLL) 형식은 보안상 첨부할 수 없습니다.'));
      }
      if (arr[0] === 0x7f && arr[1] === 0x45 && arr[2] === 0x4c && arr[3] === 0x46) {
        return reject(new Error('실행 바이너리(ELF) 형식은 보안상 첨부할 수 없습니다.'));
      }

      // 2. 확장자별 매직 바이트 검증
      let isValid = false;
      let signatureName = '';

      switch (extension) {
        case 'png':
          isValid = arr[0] === 0x89 && arr[1] === 0x50 && arr[2] === 0x4e && arr[3] === 0x47;
          signatureName = 'PNG';
          break;
        case 'jpg':
        case 'jpeg':
          isValid = arr[0] === 0xff && arr[1] === 0xd8 && arr[2] === 0xff;
          signatureName = 'JPEG';
          break;
        case 'gif':
          isValid = arr[0] === 0x47 && arr[1] === 0x49 && arr[2] === 0x46 && arr[3] === 0x38;
          signatureName = 'GIF';
          break;
        case 'webp':
          isValid =
            arr[0] === 0x52 && arr[1] === 0x49 && arr[2] === 0x46 && arr[3] === 0x46 && // RIFF
            arr[8] === 0x57 && arr[9] === 0x45 && arr[10] === 0x42 && arr[11] === 0x50; // WEBP
          signatureName = 'WEBP';
          break;
        case 'pdf':
          isValid = arr[0] === 0x25 && arr[1] === 0x50 && arr[2] === 0x44 && arr[3] === 0x46; // %PDF
          signatureName = 'PDF';
          break;
        case 'zip':
        case 'docx':
        case 'xlsx':
        case 'pptx':
        case 'hwpx':
          isValid = arr[0] === 0x50 && arr[1] === 0x4b && arr[2] === 0x03 && arr[3] === 0x04; // PK..
          signatureName = 'ZIP/Office';
          break;
        case 'doc':
        case 'xls':
        case 'ppt':
        case 'hwp':
          isValid = arr[0] === 0xd0 && arr[1] === 0xcf && arr[2] === 0x11 && arr[3] === 0xe0; // OLE2
          signatureName = '문서';
          break;
        default:
          isValid = true;
          break;
      }

      if (!isValid) {
        return reject(
          new Error(`확장자(.${extension})와 실제 파일 내부 시그니처(${signatureName || '알 수 없음'})가 일치하지 않습니다. (위조 파일 의심)`)
        );
      }

      resolve(true);
    };

    reader.onerror = () => reject(new Error('파일 헤더 검사 중 오류가 발생했습니다.'));
    reader.readAsArrayBuffer(slice);
  });
}

/**
 * 파일 보안 종합 검증 함수
 * @param {File} file
 * @returns {Promise<{ valid: boolean, error?: string, extension: string, isImage: boolean }>}
 */
export async function validateSecurity(file) {
  if (!file) {
    return { valid: false, error: '선택된 파일이 없습니다.' };
  }

  // 1. 파일 크기 검사
  if (file.size === 0) {
    return { valid: false, error: '빈 파일(0 byte)은 첨부할 수 없습니다.' };
  }
  if (file.size > MAX_FILE_SIZE_BYTES) {
    const mb = (file.size / (1024 * 1024)).toFixed(1);
    return { valid: false, error: `파일 용량(${mb}MB)이 최대 허용치(15MB)를 초과하였습니다.` };
  }

  const filename = file.name || '';
  const extension = getFileExtension(filename);

  // 2. 확장자 검사
  if (!extension) {
    return { valid: false, error: '확장자가 없는 파일은 보안상 첨부할 수 없습니다.' };
  }

  if (!ALLOWED_EXTENSIONS.has(extension)) {
    return {
      valid: false,
      error: `지원하지 않는 파일 형식(.${extension})입니다. 안전한 문서(PDF, Office, HWP, TXT) 및 이미지(PNG, JPG, WEBP, GIF)만 허용됩니다.`
    };
  }

  // 3. 다중 확장자 악성코드 방어 (예: payload.exe.png, script.php.jpg)
  const parts = filename.toLowerCase().split('.');
  if (parts.length > 2) {
    for (let i = 1; i < parts.length - 1; i++) {
      const subExt = parts[i];
      if (DANGEROUS_EXTENSIONS.has(subExt)) {
        return {
          valid: false,
          error: `파일명에 잠재적으로 위험한 실행 확장자(.${subExt})가 포함되어 있어 차단되었습니다.`
        };
      }
    }
  }

  // 4. 경로 조작 문자 검사
  if (filename.includes('..') || filename.includes('/') || filename.includes('\\') || filename.includes('\0')) {
    return { valid: false, error: '파일명에 허용되지 않는 특수문자 또는 경로 조작 문자가 포함되어 있습니다.' };
  }

  // 5. 파일 매직 바이트 검증
  try {
    await validateFileMagicBytes(file, extension);
  } catch (err) {
    return { valid: false, error: err.message };
  }

  const isImage = IMAGE_EXTENSIONS.has(extension);
  return { valid: true, extension, isImage };
}

export function isSafeAttachmentUrl(url) {
  if (!url || typeof url !== 'string') return false;
  const trimmed = url.trim();
  return (
    trimmed.startsWith('/api/') ||
    trimmed.startsWith('http://') ||
    trimmed.startsWith('https://') ||
    trimmed.startsWith('blob:')
  );
}

/**
 * 용량 읽기 쉬운 포맷 변환 (예: 1.2 MB, 340 KB)
 */
export function formatFileSize(bytes) {
  if (bytes === 0 || !bytes) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}
