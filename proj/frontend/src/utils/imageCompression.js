// src/utils/imageCompression.js
// 클라이언트단 고효율 이미지 리사이징 및 손실 압축 유틸리티

const MAX_WIDTH = 1920;
const MAX_HEIGHT = 1920;
const COMPRESSION_QUALITY = 0.82;

/**
 * 이미지 압축 및 리사이징 수행
 * @param {File} file 원본 이미지 파일
 * @returns {Promise<{
 *   compressedFile: File,
 *   originalSize: number,
 *   compressedSize: number,
 *   isCompressed: boolean,
 *   compressionRatio: number,
 *   previewUrl: string
 * }>}
 */
export async function compressImageClient(file) {
  const originalSize = file.size;

  // GIF 애니메이션 등은 Canvas 변환 시 프레임 손실이 있을 수 있으므로 그대로 유지
  if (file.type === 'image/gif') {
    const previewUrl = URL.createObjectURL(file);
    return {
      compressedFile: file,
      originalSize,
      compressedSize: originalSize,
      isCompressed: false,
      compressionRatio: 0,
      previewUrl
    };
  }

  return new Promise((resolve) => {
    const img = new Image();
    const objectUrl = URL.createObjectURL(file);

    img.onload = () => {
      URL.revokeObjectURL(objectUrl);

      let { width, height } = img;
      let needResize = false;

      // 최대 해상도 초과 시 비율 유지 다운스케일링
      if (width > MAX_WIDTH || height > MAX_HEIGHT) {
        needResize = true;
        if (width > height) {
          height = Math.round((height * MAX_WIDTH) / width);
          width = MAX_WIDTH;
        } else {
          width = Math.round((width * MAX_HEIGHT) / height);
          height = MAX_HEIGHT;
        }
      }

      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;

      const ctx = canvas.getContext('2d');
      if (!ctx) {
        // Canvas 미지원 시 원본 반환
        return resolve({
          compressedFile: file,
          originalSize,
          compressedSize: originalSize,
          isCompressed: false,
          compressionRatio: 0,
          previewUrl: URL.createObjectURL(file)
        });
      }

      // 이미지 스무딩 설정
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(img, 0, 0, width, height);

      // 출력 포맷 결정 (투명도가 있는 PNG는 PNG 유지, 나머지는 JPEG/WebP로 고효율 압축)
      const outputType = file.type === 'image/png' ? 'image/png' : 'image/jpeg';
      const quality = outputType === 'image/jpeg' ? COMPRESSION_QUALITY : undefined;

      canvas.toBlob(
        (blob) => {
          if (!blob) {
            return resolve({
              compressedFile: file,
              originalSize,
              compressedSize: originalSize,
              isCompressed: false,
              compressionRatio: 0,
              previewUrl: URL.createObjectURL(file)
            });
          }

          const compressedSize = blob.size;
          // 압축 후 용량이 원본보다 작거나 리사이징된 경우 압축본 채택
          if (compressedSize < originalSize || needResize) {
            const compressedFile = new File([blob], file.name, {
              type: outputType,
              lastModified: Date.now()
            });

            const savedBytes = Math.max(0, originalSize - compressedSize);
            const compressionRatio = Math.round((savedBytes / originalSize) * 1000) / 10;
            const previewUrl = URL.createObjectURL(compressedFile);

            resolve({
              compressedFile,
              originalSize,
              compressedSize,
              isCompressed: true,
              compressionRatio,
              previewUrl
            });
          } else {
            // 원본이 이미 충분히 작은 경우 원본 유지
            resolve({
              compressedFile: file,
              originalSize,
              compressedSize: originalSize,
              isCompressed: false,
              compressionRatio: 0,
              previewUrl: URL.createObjectURL(file)
            });
          }
        },
        outputType,
        quality
      );
    };

    img.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      // 로드 실패 시 원본 그대로 유지
      resolve({
        compressedFile: file,
        originalSize,
        compressedSize: originalSize,
        isCompressed: false,
        compressionRatio: 0,
        previewUrl: URL.createObjectURL(file)
      });
    };

    img.src = objectUrl;
  });
}
