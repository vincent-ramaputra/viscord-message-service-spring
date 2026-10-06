package com.viscord.message_service.enums;

import lombok.Getter;

@Getter
public enum StoragePath {
    ATTACHMENT("messages/attachments"),
    PENDING("pending");


    private final String path;

    StoragePath(String path) {
        this.path = path;
    }

}
