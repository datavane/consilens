package com.consilens.cluster.application;

import java.io.IOException;

interface ComparisonDescriptorOpener {

    ComparisonDescriptor open(String reference) throws IOException;
}
