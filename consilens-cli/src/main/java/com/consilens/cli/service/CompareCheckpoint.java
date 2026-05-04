package com.consilens.cli.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompareCheckpoint {

    private String taskId;

    private Instant watermark;

    private Instant lastStart;

    private Instant lastEnd;

    private String status;

    private String owner;

    private Instant leaseUntil;
}
