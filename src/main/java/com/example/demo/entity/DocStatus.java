package com.example.demo.entity;

public enum DocStatus {
    PENDING,    // 待处理（刚上传）
    PROCESSING, // 处理中（正在切片、Embedding）
    COMPLETED,  // 完成
    FAILED      // 失败
}
