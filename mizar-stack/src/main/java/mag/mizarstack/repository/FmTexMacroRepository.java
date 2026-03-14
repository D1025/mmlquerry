package mag.mizarstack.repository;

import mag.mizarstack.entity.FmTexMacro;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FmTexMacroRepository extends JpaRepository<FmTexMacro, UUID> {
}
