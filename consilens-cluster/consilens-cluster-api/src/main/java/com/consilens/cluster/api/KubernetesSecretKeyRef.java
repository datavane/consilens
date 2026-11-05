package com.consilens.cluster.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Reference to one Kubernetes Secret key. Secret values are intentionally not
 * serializable through the cluster submission contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KubernetesSecretKeyRef implements Serializable {

    private static final long serialVersionUID = 1L;

    private String secretName;

    private String secretKey;
}
