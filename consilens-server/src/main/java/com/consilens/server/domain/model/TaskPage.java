package com.consilens.server.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class TaskPage {

    private final long total;
    private final List<TaskInstanceRecord> items;
}
