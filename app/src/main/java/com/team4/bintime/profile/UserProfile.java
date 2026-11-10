package com.team4.bintime.profile;

import androidx.annotation.NonNull;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 사용자 프로필 모델 (BT-004 / Issue #4).
 * Firestore `users/{uid}` 및 `publicProfiles/{uid}` 설계 계약 준수.
 */
public class UserProfile implements Serializable {

    private String uid;
    private String email;
    private String nickname;
    private String department;
    private String grade;
    private String gender;
    private String bio;
    private int photoResId;
    /** Cloud Storage 프로필 사진 경로 (예: profileImages/{uid}/profile.jpg). 없으면 null. (BT-014) */
    private String photoPath;
    private List<String> interestTags;

    public UserProfile() {
        this.uid = "u1";
        this.email = "yu.bin@kookmin.ac.kr";
        this.nickname = "김지민";
        this.department = "소프트웨어학과";
        this.grade = "1학년";
        this.gender = "여성";
        this.bio = "같이 공부할 친구 찾아요!\n(스터디, 산책, 카페 다 좋아해요 😊)";
        this.interestTags = new ArrayList<>();
        this.interestTags.add("스터디");
        this.interestTags.add("카페");
        this.interestTags.add("산책");
    }

    /** 프로필 최초 생성용 빈 프로필. 기본 시연 값(김지민 등)을 채우지 않는다. */
    public static UserProfile empty() {
        UserProfile p = new UserProfile();
        p.uid = null;
        p.email = null;
        p.nickname = "";
        p.department = "";
        p.grade = "";
        p.gender = "";
        p.bio = "";
        p.photoResId = 0;
        p.photoPath = null;
        p.interestTags = new ArrayList<>();
        return p;
    }

    /** 저장소 내부 값과 화면 편집 값을 분리하기 위한 깊은 복사. */
    public UserProfile copy() {
        UserProfile p = new UserProfile();
        p.uid = uid;
        p.email = email;
        p.nickname = nickname;
        p.department = department;
        p.grade = grade;
        p.gender = gender;
        p.bio = bio;
        p.photoResId = photoResId;
        p.photoPath = photoPath;
        p.interestTags = interestTags != null ? new ArrayList<>(interestTags) : new ArrayList<>();
        return p;
    }

    public static UserProfile createDefault() {
        UserProfile p = new UserProfile();
        p.setGrade("3학년");
        p.setDepartment("컴퓨터공학과");
        return p;
    }

    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public int getPhotoResId() { return photoResId; }
    public void setPhotoResId(int photoResId) { this.photoResId = photoResId; }

    public String getPhotoPath() { return photoPath; }
    public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }

    public List<String> getInterestTags() { return interestTags; }
    public void setInterestTags(List<String> interestTags) {
        this.interestTags = interestTags != null ? new ArrayList<>(interestTags) : new ArrayList<>();
    }

    public String getFormattedSummary() {
        // 예: "컴퓨터공학과 3학년 · 여성"
        StringBuilder sb = new StringBuilder();
        if (department != null && !department.isEmpty()) sb.append(department).append(" ");
        if (grade != null && !grade.isEmpty()) sb.append(grade);
        if (gender != null && !gender.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(gender);
        }
        return sb.toString();
    }
}
