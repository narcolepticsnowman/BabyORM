package com.babyorm.db;

import com.babyorm.annotation.TableName;

import java.util.UUID;

@TableName("no_autogen")
public class EntityWithNonAutogenPK {
    private UUID pk;
    private String name;
}