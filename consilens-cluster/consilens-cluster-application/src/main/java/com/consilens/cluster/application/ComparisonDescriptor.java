package com.consilens.cluster.application;

import java.io.IOException;
import java.io.InputStream;

interface ComparisonDescriptor {

    InputStream open() throws IOException;

    String format();
}
