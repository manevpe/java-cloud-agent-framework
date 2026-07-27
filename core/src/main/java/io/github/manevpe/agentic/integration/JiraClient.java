package io.github.manevpe.agentic.integration;

/**
 * Port to Jira. Kept deliberately narrow to what the framework currently
 * needs — posting the drafted plan back to a ticket as a comment.
 */
public interface JiraClient {

    void postComment(String ticketKey, String text);
}
