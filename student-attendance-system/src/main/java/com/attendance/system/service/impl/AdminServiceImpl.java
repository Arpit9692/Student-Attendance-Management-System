package com.attendance.system.service.impl;

import com.attendance.system.dto.request.*;
import com.attendance.system.dto.response.*;
import com.attendance.system.entity.*;
import com.attendance.system.repository.*;
import com.attendance.system.service.AdminService;
import com.attendance.system.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication; // Added
import org.springframework.security.core.context.SecurityContextHolder; // Added
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdminServiceImpl implements AdminService {

    @Autowired private TeacherRepository teacherRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private UnlockRequestRepository unlockRequestRepository;
    @Autowired private AttendanceRepository attendanceRepository; 
    @Autowired private AuthService authService; 
    @Autowired private PasswordEncoder passwordEncoder;
    // NOTE: If you have an AdminRepository, it should be autowired here.

    // =================================================
    // DASHBOARD & RECENT ACTIVITY
    // =================================================
    // ... (All Dashboard and Helper methods remain unchanged)
    
    @Override
    public DashboardResponse getAdminDashboard() {
        DashboardResponse response = new DashboardResponse();
        response.setTotalTeachers(teacherRepository.countByActiveTrue());
        response.setTotalStudents(studentRepository.countByActiveTrue());
        response.setTotalClasses(classRepository.countAllClasses());
        response.setTotalCourses(courseRepository.countAllCourses());
        
        // ✅ POPULATE RECENT ACTIVITY
        response.setRecentActivities(generateRecentActivities());
        
        return response;
    }

    private List<ActivityDTO> generateRecentActivities() {
        List<ActivityDTO> activities = new ArrayList<>();

        // 1. Get recent Unlock Requests (Limit 3)
        List<UnlockRequest> requests = unlockRequestRepository.findAllByOrderByCreatedAtDesc();
        int limit = Math.min(requests.size(), 3);
        for (int i = 0; i < limit; i++) {
            UnlockRequest req = requests.get(i);
            String desc = "Unlock request " + req.getStatus().name().toLowerCase() + " for " + 
                          (req.getTeacher() != null ? req.getTeacher().getName() : "Teacher");
            
            activities.add(new ActivityDTO(
                desc,
                getTimeAgo(req.getCreatedAt()),
                "bi-lock",
                req.getStatus() == UnlockRequest.Status.PENDING ? "warning" : "info",
                req.getCreatedAt()
            ));
        }

        // 2. Get recent Attendance Marked (Limit 3)
        // Note: Requires 'findTop5ByOrderByMarkedAtDesc' in AttendanceRepository
        List<Attendance> attendances = attendanceRepository.findTop5ByOrderByMarkedAtDesc();
        Set<String> seenSessions = new HashSet<>();
        
        for (Attendance att : attendances) {
            if (att.getMarkedAt() == null) continue;
            
            String key = att.getCourse().getId() + "-" + att.getDate();
            if (!seenSessions.contains(key)) {
                seenSessions.add(key);
                String desc = "Attendance marked for " + att.getCourse().getCourseName();
                
                activities.add(new ActivityDTO(
                    desc,
                    getTimeAgo(att.getMarkedAt()),
                    "bi-check-circle",
                    "success",
                    att.getMarkedAt()
                ));
            }
        }

        // 3. Sort combined list by latest first and pick top 5
        return activities.stream()
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .limit(5)
                .collect(Collectors.toList());
    }

    private String getTimeAgo(LocalDateTime time) {
        if (time == null) return "Just now";
        Duration duration = Duration.between(time, LocalDateTime.now());
        long minutes = duration.toMinutes();
        
        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + " mins ago";
        long hours = duration.toHours();
        if (hours < 24) return hours + " hours ago";
        long days = duration.toDays();
        return days + " days ago";
    }

    // =================================================
    // UNLOCK REQUESTS
    // =================================================

    @Override
    public List<UnlockRequest> getAllUnlockRequests() {
        return unlockRequestRepository.findAllByOrderByCreatedAtDesc();
    }

    @Override
    public UnlockStatsResponse getUnlockStats() {
        long pending = unlockRequestRepository.countByStatus(UnlockRequest.Status.PENDING);
        long approved = unlockRequestRepository.countByStatus(UnlockRequest.Status.APPROVED);
        long rejected = unlockRequestRepository.countByStatus(UnlockRequest.Status.REJECTED);
        long total = pending + approved + rejected;
        
        return new UnlockStatsResponse(total, pending, approved, rejected);
    }

    @Override
    public List<UnlockRequest> getPendingUnlockRequests() {
        return unlockRequestRepository.findByStatus(UnlockRequest.Status.PENDING);
    }

    /**
     * FIX: Processes the unlock request and updates the corresponding Attendance 
     * records to be unlocked (isLocked = false) if approved.
     */
    @Override
    @Transactional
    public UnlockRequest processUnlockRequest(Long requestId, boolean approve) {
        UnlockRequest request = unlockRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Unlock request not found"));
        
        request.setStatus(approve ? UnlockRequest.Status.APPROVED : UnlockRequest.Status.REJECTED);

        if (approve) {
            // Get the ID of the Admin currently processing the request (Optional, for tracking)
            Long adminId = getAdminUserId(); 

            // 1. Find all attendance records for the specific course and date
            List<Attendance> recordsToUnlock = attendanceRepository.findByCourseAndDate(
            	    request.getCourse(), 
            	    request.getRequestDate() // <--- Check your UnlockRequest entity for the exact name
            	);

            // 2. Update the lock status on the attendance records
            for (Attendance record : recordsToUnlock) {
                record.setIsLocked(false);
                record.setUnlockApprovedBy(adminId); 
            }
            
            // 3. Save the unlocked attendance records
            attendanceRepository.saveAll(recordsToUnlock);
        }

        // 4. Save the updated UnlockRequest status
        return unlockRequestRepository.save(request);
    }

    /** Helper method to get the current logged-in user's ID. Assuming Admin is a User. */
    private Long getAdminUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            return user.getId();
        }
        // Fallback or handle unauthenticated case (optional)
        return null; 
    }

    // =================================================
    // REPORT LOGIC
    // =================================================
    // ... (All Report and CRUD methods remain unchanged)
    
    @Override
    public List<AttendanceReportResponse> getAttendanceReports() {
        List<Attendance> allAttendance = attendanceRepository.findAll();

        Map<String, List<Attendance>> groupedBySession = allAttendance.stream()
                .collect(Collectors.groupingBy(a -> 
                    a.getCourse().getId() + "_" + a.getDate().toString()
                ));

        return groupedBySession.values().stream()
                .map(sessionList -> {
                    if (sessionList.isEmpty()) return null;
                    
                    Attendance firstRecord = sessionList.get(0);
                    Course course = firstRecord.getCourse();
                    ClassEntity classEntity = course.getClassEntity();
                    
                    int totalStudents = sessionList.size();
                    int presentCount = (int) sessionList.stream()
                            .filter(this::isPresent) 
                            .count();
                    int absentCount = totalStudents - presentCount;
                    
                    // Prevent Division by Zero
                    double percentage = 0.0;
                    if (totalStudents > 0) {
                        percentage = ((double) presentCount / totalStudents) * 100;
                    }
                    
                    percentage = Math.round(percentage * 10.0) / 10.0;
                    String status = determineStatus(percentage);

                    return new AttendanceReportResponse(
                        classEntity != null ? classEntity.getClassName() + " " + classEntity.getSection() : "-",
                        course.getCourseName(),
                        course.getTeacher() != null ? course.getTeacher().getName() : "Unknown",
                        firstRecord.getDate(),
                        totalStudents,
                        presentCount,
                        absentCount,
                        percentage, 
                        status
                    );
                })
                .filter(java.util.Objects::nonNull)
                .sorted((r1, r2) -> r2.getDate().compareTo(r1.getDate()))
                .collect(Collectors.toList());
    }

    private boolean isPresent(Attendance a) {
        return a.getStatus() != null && "PRESENT".equalsIgnoreCase(String.valueOf(a.getStatus())); 
    }

    private String determineStatus(double percentage) {
        if (percentage >= 90) return "Excellent";
        if (percentage >= 75) return "Good";
        if (percentage >= 60) return "Average";
        return "Needs Attention";
    }