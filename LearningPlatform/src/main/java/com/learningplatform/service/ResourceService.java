
package com.learningplatform.service;

import com.learningplatform.entity.Resource;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.ResourceRepository;
import com.learningplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ResourceService {

    private static final Logger logger = LoggerFactory.getLogger(ResourceService.class);

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private CourseService courseService;

    @Autowired
    private ChapterService chapterService;

    private static final String UPLOAD_DIR = "uploads/resources/";

    @Transactional
    public Resource createResource(Resource resource) {
        courseService.getCourseById(resource.getCourseId());
        
        if (resource.getChapterId() != null && !resource.getChapterId().isEmpty()) {
            chapterService.validateChapterBelongsToCourse(resource.getChapterId(), resource.getCourseId());
        }
        
        if (resource.getResourceId() == null || resource.getResourceId().isEmpty()) {
            resource.setResourceId(IdGenerator.generateResourceId());
        }
        if (resource.getResourceStatus() == null) {
            resource.setResourceStatus("active");
        }
        Resource saved = resourceRepository.save(resource);
        logger.info("创建资源成功: {}", saved.getResourceId());
        return saved;
    }

    @Transactional
    public Resource uploadResource(String courseId, String chapterId, MultipartFile file, String uploadedBy) {
        courseService.getCourseById(courseId);
        
        if (chapterId != null && !chapterId.isEmpty()) {
            chapterService.validateChapterBelongsToCourse(chapterId, courseId);
        }

        try {
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String newFilename = UUID.randomUUID().toString().replace("-", "") + extension;
            
            Path uploadPath = Paths.get(UPLOAD_DIR + courseId);
            Files.createDirectories(uploadPath);
            
            Path filePath = uploadPath.resolve(newFilename);
            file.transferTo(filePath.toFile());

            Resource resource = new Resource();
            resource.setResourceId(IdGenerator.generateResourceId());
            resource.setCourseId(courseId);
            resource.setChapterId(chapterId);
            resource.setResourceName(originalFilename != null ? originalFilename : newFilename);
            resource.setResourceType(getResourceType(extension));
            resource.setResourcePath(filePath.toString());
            resource.setResourceUrl("/resources/" + courseId + "/" + newFilename);
            resource.setResourceSize(file.getSize());
            resource.setResourceStatus("active");
            resource.setUploadedBy(uploadedBy);

            Resource saved = resourceRepository.save(resource);
            logger.info("上传资源成功: {}, 大小: {} bytes", saved.getResourceId(), file.getSize());
            return saved;
        } catch (IOException e) {
            logger.error("上传资源失败", e);
            throw new BusinessException("上传资源失败: " + e.getMessage());
        }
    }

    private String getResourceType(String extension) {
        String ext = extension.toLowerCase();
        if (ext.contains("mp4") || ext.contains("avi") || ext.contains("mov") || ext.contains("mkv")) {
            return "video";
        } else if (ext.contains("mp3") || ext.contains("wav") || ext.contains("flac")) {
            return "audio";
        } else if (ext.contains("pdf") || ext.contains("doc") || ext.contains("docx") || ext.contains("ppt") || ext.contains("pptx")) {
            return "document";
        } else if (ext.contains("jpg") || ext.contains("jpeg") || ext.contains("png") || ext.contains("gif")) {
            return "image";
        }
        return "other";
    }

    public Resource getResourceById(String resourceId) {
        return resourceRepository.findById(resourceId)
                .orElseThrow(() -> new BusinessException(404, "资源不存在: " + resourceId));
    }

    public Optional<Resource> findResourceById(String resourceId) {
        return resourceRepository.findById(resourceId);
    }

    public List<Resource> getResourcesByCourse(String courseId) {
        return resourceRepository.findByCourseId(courseId);
    }

    public List<Resource> getResourcesByChapter(String chapterId) {
        return resourceRepository.findByChapterId(chapterId);
    }

    public List<Resource> getActiveResourcesByCourse(String courseId) {
        return resourceRepository.findByCourseIdAndResourceStatus(courseId, "active");
    }

    @Transactional
    public Resource updateResource(String resourceId, Resource resource) {
        Resource existing = getResourceById(resourceId);
        if (resource.getResourceName() != null) {
            existing.setResourceName(resource.getResourceName());
        }
        if (resource.getResourceType() != null) {
            existing.setResourceType(resource.getResourceType());
        }
        if (resource.getResourceStatus() != null) {
            existing.setResourceStatus(resource.getResourceStatus());
        }
        Resource saved = resourceRepository.save(existing);
        logger.info("更新资源成功: {}", resourceId);
        return saved;
    }

    @Transactional
    public void deleteResource(String resourceId) {
        Resource resource = getResourceById(resourceId);
        if (resource.getResourcePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(resource.getResourcePath()));
            } catch (IOException e) {
                logger.warn("删除资源文件失败: {}", resource.getResourcePath(), e);
            }
        }
        resourceRepository.delete(resource);
        logger.info("删除资源成功: {}", resourceId);
    }
}
