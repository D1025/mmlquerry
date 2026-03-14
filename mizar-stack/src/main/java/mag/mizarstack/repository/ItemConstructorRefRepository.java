package mag.mizarstack.repository;

import mag.mizarstack.entity.ItemConstructorRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ItemConstructorRefRepository extends JpaRepository<ItemConstructorRef, UUID> {
    boolean existsByItemIdAndConstructorItemIdAndRole(UUID itemId, UUID constructorItemId, String role);
}
