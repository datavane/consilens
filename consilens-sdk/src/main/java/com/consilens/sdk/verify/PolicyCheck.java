package com.consilens.sdk.verify;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyCheck {

    private String name;

    /** null means "not evaluable" (e.g. missing baseline) and does not fail the gate. */
    private Boolean passed;

    private Object actual;
    private Object limit;
    private String message;
}
