package io.github.manevpe.agentic.config;

/** Thrown when a workflow YAML file is malformed or fails structural validation. */
public class WorkflowConfigException extends RuntimeException {

    public WorkflowConfigException(String message) {
        super(message);
    }

    public WorkflowConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
