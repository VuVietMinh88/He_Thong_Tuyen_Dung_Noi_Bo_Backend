package vn.ttcs.recruitment.companyprofile;

import java.util.UUID;

// The metadata columns of company_media without the image bytes (see CompanyMediaRepository).
public record CompanyMediaSummary(UUID id, CompanyMediaKind kind, int width, int height) { }
