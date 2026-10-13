package vn.ttcs.recruitment.companyprofile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyMediaRepository extends JpaRepository<CompanyMedia, UUID> {

    // Selects only the metadata columns: loading CompanyMedia entities would also load every image's bytes.
    @Query("""
            select new vn.ttcs.recruitment.companyprofile.CompanyMediaSummary(m.id, m.kind, m.width, m.height)
            from CompanyMedia m
            where m.id in :ids
            """)
    List<CompanyMediaSummary> findSummaries(@Param("ids") Collection<UUID> ids);

    // The picture with its bytes, but only while the saved page uses it as the logo or as an introduction image.
    @Query("""
            select m from CompanyMedia m
            where m.id = :id
              and (exists (select p from CompanyProfile p where p.logoMediaId = m.id)
                   or exists (select p from CompanyProfile p join p.imageIds imageId where imageId = m.id))
            """)
    Optional<CompanyMedia> findPublished(@Param("id") UUID id);
}
