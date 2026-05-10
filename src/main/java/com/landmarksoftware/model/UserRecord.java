package com.landmarksoftware.model;

import java.time.LocalDate;

/**
 * Immutable snapshot of a meusers row, returned by UserService.findUser().
 *
 * The storedPassword field contains whatever is currently in the DB — either
 * a BCrypt hash (after migration) or a plain-text legacy value.
 * PasswordService decides how to compare it.
 *
 * userStatus: blank=active, H=suspended, L=locked, T=terminated.
 */
public record UserRecord(
    String    userId,
    String    name1,
    String    storedPassword,
    String    userStatus,
    String    supervisorFlag,
    LocalDate passwdExpiry
) {}
