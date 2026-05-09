package com.josh.interviewj.common.exception;

/**
 * Centralized business error codes used by {@link BusinessException} and HTTP mapping.
 *
 * <p>Keep values stable once released.</p>
 */
public final class ErrorCode {

    private ErrorCode() {
    }

    // Auth
    public static final String AUTH_001 = "AUTH_001";
    public static final String AUTH_002 = "AUTH_002";
    public static final String AUTH_003 = "AUTH_003";
    public static final String AUTH_004 = "AUTH_004";
    public static final String AUTH_005 = "AUTH_005";
    public static final String AUTH_006 = "AUTH_006";
    public static final String AUTH_007 = "AUTH_007";
    public static final String AUTH_008 = "AUTH_008";

    // User
    public static final String USER_001 = "USER_001";
    public static final String USER_002 = "USER_002";
    public static final String USER_003 = "USER_003";
    public static final String USER_004 = "USER_004";
    public static final String USER_CREDIT_002 = "USER_CREDIT_002";
    public static final String USER_BILLING_001 = "USER_BILLING_001";
    public static final String USER_BILLING_002 = "USER_BILLING_002";
    public static final String USER_BILLING_003 = "USER_BILLING_003";
    public static final String USER_BILLING_004 = "USER_BILLING_004";
    public static final String USER_BILLING_005 = "USER_BILLING_005";
    public static final String USER_BILLING_006 = "USER_BILLING_006";

    // Payment
    public static final String PAYMENT_001 = "PAYMENT_001";
    public static final String PAYMENT_002 = "PAYMENT_002";
    public static final String PAYMENT_003 = "PAYMENT_003";
    public static final String PAYMENT_004 = "PAYMENT_004";
    public static final String PAYMENT_005 = "PAYMENT_005";
    public static final String PAYMENT_006 = "PAYMENT_006";

    // Invite
    public static final String INVITE_001 = "INVITE_001";
    public static final String INVITE_002 = "INVITE_002";
    public static final String INVITE_003 = "INVITE_003";
    public static final String INVITE_004 = "INVITE_004";


    // Resume
    public static final String RESUME_001 = "RESUME_001";
    public static final String RESUME_002 = "RESUME_002";
    public static final String RESUME_003 = "RESUME_003";
    public static final String RESUME_004 = "RESUME_004";
    public static final String RESUME_005 = "RESUME_005";
    public static final String RESUME_006 = "RESUME_006";
    public static final String RESUME_007 = "RESUME_007";
    public static final String RESUME_008 = "RESUME_008";
    public static final String RESUME_009 = "RESUME_009";
    public static final String RESUME_010 = "RESUME_010";

    // Resume Job Match extension
    public static final String RESUME_011 = "RESUME_011";
    public static final String RESUME_012 = "RESUME_012";
    public static final String RESUME_013 = "RESUME_013";
    public static final String RESUME_014 = "RESUME_014";
    public static final String RESUME_015 = "RESUME_015";

    // Infra / Integration
    public static final String FILE_001 = "FILE_001";
    public static final String LLM_001 = "LLM_001";

    // Knowledge Base
    public static final String KB_001 = "KB_001";
    public static final String KB_002 = "KB_002";
    public static final String KB_003 = "KB_003";
    public static final String KB_004 = "KB_004";

    // Interview
    public static final String INTERVIEW_001 = "INTERVIEW_001";
    public static final String INTERVIEW_002 = "INTERVIEW_002";
    public static final String INTERVIEW_003 = "INTERVIEW_003";
    public static final String INTERVIEW_004 = "INTERVIEW_004";
    public static final String INTERVIEW_005 = "INTERVIEW_005";
    public static final String INTERVIEW_006 = "INTERVIEW_006";
    public static final String INTERVIEW_007 = "INTERVIEW_007";
    public static final String INTERVIEW_008 = "INTERVIEW_008";
    public static final String INTERVIEW_009 = "INTERVIEW_009";
    public static final String INTERVIEW_010 = "INTERVIEW_010";
    public static final String INTERVIEW_011 = "INTERVIEW_011";

    // Admin Credits
    public static final String ADMIN_CREDIT_001 = "ADMIN_CREDIT_001";
    public static final String ADMIN_CREDIT_002 = "ADMIN_CREDIT_002";
    public static final String ADMIN_CREDIT_003 = "ADMIN_CREDIT_003";
    public static final String ADMIN_BILLING_001 = "ADMIN_BILLING_001";
    public static final String ADMIN_BILLING_002 = "ADMIN_BILLING_002";
    public static final String ADMIN_BILLING_003 = "ADMIN_BILLING_003";
    public static final String ADMIN_BILLING_004 = "ADMIN_BILLING_004";
    public static final String ADMIN_BILLING_005 = "ADMIN_BILLING_005";

    // Admin LLM
    public static final String ADMIN_LLM_001 = "ADMIN_LLM_001";
    public static final String ADMIN_LLM_002 = "ADMIN_LLM_002";

    // Activation Code
    public static final String BILLING_ACTIVATION_001 = "BILLING_ACTIVATION_001";
    public static final String BILLING_ACTIVATION_002 = "BILLING_ACTIVATION_002";
    public static final String BILLING_ACTIVATION_003 = "BILLING_ACTIVATION_003";
    public static final String BILLING_ACTIVATION_004 = "BILLING_ACTIVATION_004";
}
