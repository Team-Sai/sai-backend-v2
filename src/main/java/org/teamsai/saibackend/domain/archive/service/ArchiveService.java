package org.teamsai.saibackend.domain.archive.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.teamsai.saibackend.domain.archive.entity.ArchiveStatus;
import org.teamsai.saibackend.domain.archive.entity.ArchiveFile;
import org.teamsai.saibackend.domain.archive.repository.ArchiveRepository;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.*;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ArchiveService {

    private final ArchiveRepository archiveRepository;

    @Getter
    @Value("${file.upload-dir}")
    private String uploadDir;

    public List<ArchiveFile> findFilesByReference(ArchiveStatus domainType, Long referenceId) {
        return archiveRepository.findByDomainTypeAndReferenceIdOrderByCreatedAtDesc(domainType, referenceId);
    }

    public List<ArchiveFile> findAllFilesByUserId(Long userId) {
        return archiveRepository.findAllByUserId(userId);
    }

    @Transactional
    public ArchiveFile saveFile(String domainType, Long referenceId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("업로드할 파일이 존재하지 않습니다.");
        }

        try {
            return saveFile(domainType, referenceId, file.getOriginalFilename(), file.getContentType(),
                    file.getInputStream(), file.getSize());
        } catch (IOException e) {
            log.error("파일 저장 중 오류 발생", e);
            throw new RuntimeException("파일 저장 처리 중 오류가 발생했습니다.", e);
        }
    }

    @Transactional
    public ArchiveFile saveFile(String domainType, Long referenceId, String originalFilename, String contentType,
                             InputStream content, long fileSize) {
        try {
            Path dirPath = Paths.get(uploadDir);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }

            String ext = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
            }

            String savedFilename = domainType + "_" + referenceId + "_" + UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);

            Path savePath = dirPath.resolve(savedFilename);
            Files.copy(content, savePath, StandardCopyOption.REPLACE_EXISTING);

            ArchiveFile file = ArchiveFile.builder()
                        .domainType(ArchiveStatus.valueOf(domainType))
                        .referenceId(referenceId)
                        .originalFilename(originalFilename)
                        .savedFilename(savedFilename)
                        .fileSize(fileSize)
                        .fileType(contentType)
                        .build();

            archiveRepository.save(file);

            log.info("[ArchiveFile Saved] Domain: {}, RefId: {}, Original: {} -> Saved: {}",
                    domainType, referenceId, originalFilename, savedFilename);

            return file;

        } catch (IOException e) {
            log.error("파일 저장 중 오류 발생", e);
            throw new RuntimeException("파일 저장 처리 중 오류가 발생했습니다.", e);
        }
    }

    private static final int SEAL_RED_RGB = 0xC0272D;

    public String loadSignatureDataUri(String savedFilename) {
        if (savedFilename == null || savedFilename.isBlank()) {
            return null;
        }

        try {
            Path filePath = Paths.get(uploadDir).resolve(savedFilename).normalize();
            byte[] bytes = Files.readAllBytes(filePath);

            BufferedImage original = ImageIO.read(new ByteArrayInputStream(bytes));
            if (original == null) {
                String mimeType = savedFilename.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
                return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
            }

            byte[] recolored = recolorToSealRed(original);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(recolored);
        } catch (Exception e) {
            log.warn("서명 이미지 로딩 실패 - fileName: {}", savedFilename, e);
            return null;
        }
    }

    private byte[] recolorToSealRed(BufferedImage original) throws IOException {
        boolean hasAlpha = original.getColorModel().hasAlpha();

        BufferedImage recolored = new BufferedImage(
                original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB
        );

        for (int y = 0; y < original.getHeight(); y++) {
            for (int x = 0; x < original.getWidth(); x++) {
                int argb = original.getRGB(x, y);
                int alpha = hasAlpha ? (argb >>> 24) & 0xFF : opacityFromBrightness(argb);
                recolored.setRGB(x, y, (alpha << 24) | SEAL_RED_RGB);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(recolored, "png", out);
        return out.toByteArray();
    }

    private int opacityFromBrightness(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int brightness = (r + g + b) / 3;
        return 255 - brightness;
    }

    public ArchiveFile getFileById(Long fileId) {
        return archiveRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 파일입니다. fileId=" + fileId));
    }


    public Resource loadFileAsResource(String savedFilename) {
        try {
            Path filePath = Paths.get(uploadDir).resolve(savedFilename).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                log.error("파일을 찾을 수 없거나 읽을 수 없습니다: {}", filePath);
                throw new RuntimeException("파일을 찾을 수 없거나 읽을 수 없습니다.");
            }
        } catch (MalformedURLException e) {
            log.error("파일 경로 해석 오류: {}", savedFilename, e);
            throw new RuntimeException("파일 경로 해석 중 오류가 발생했습니다.", e);
        }
    }
}
