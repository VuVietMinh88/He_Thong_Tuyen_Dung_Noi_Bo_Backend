package vn.ttcs.recruitment.companyprofile;

import java.util.List;

// What candidates see on the recruitment portal, and exactly what the preview returns.
// It has no editor data (who saved it, when) and lists images in display order.
public record PublicCompanyProfileView(String companyName, String tagline, String introduction,
                                       CompanyMediaView logo, List<CompanyMediaView> images) { }
