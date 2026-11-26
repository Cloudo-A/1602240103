package com.adc.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "app_user")
class AppUser {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    String username;
    String passwordHash;
    String displayName;
    String role;
    Long dentistId;
    boolean active;
    @Version int version;

    CurrentUser currentUser() {
        return new CurrentUser(id, username, displayName, role, dentistId);
    }
}
