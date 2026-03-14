package mag.mizarstack.repository;

import mag.mizarstack.entity.NotationFormat;
import mag.mizarstack.entity.id.NotationFormatId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotationFormatRepository extends JpaRepository<NotationFormat, NotationFormatId> {
}
