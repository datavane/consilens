FROM mcr.microsoft.com/openjdk/jdk:11-ubuntu

ARG JAR_PATH=consilens-cluster/consilens-cluster-application/target/consilens-cluster-application-0.1-SNAPSHOT.jar

WORKDIR /opt/consilens

COPY ${JAR_PATH} runtime/consilens-cluster-application.jar
