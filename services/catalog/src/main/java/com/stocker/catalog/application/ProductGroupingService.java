package com.stocker.catalog.application;

import com.stocker.catalog.domain.ProductGroupKey;
import com.stocker.catalog.infrastructure.persistence.ItemEntity;
import com.stocker.catalog.infrastructure.persistence.SpringDataItemRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProductGroupingService {
    private final SpringDataItemRepository itemRepository;

    public ProductGroupingService(SpringDataItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    public List<ProductGroupPreview> previewGroups() {
        List<ItemEntity> items = itemRepository.findAll();

        Map<ProductGroupKey, List<ItemEntity>> groups = items.stream().collect(Collectors.groupingBy(this::createKey));

        return groups.entrySet().stream().filter(entry -> entry.getValue().size() >= 2).map(entry -> {
            ProductGroupKey key = entry.getKey();
            List<ItemEntity> groupedItems = entry.getValue();

            return new ProductGroupPreview(
                    key.normalizedName(),
                    key.groceryType(),
                    key.weightKg(),
                    key.volumeL(),
                    groupedItems.size(),
                    needsReview(groupedItems),
                    groupedItems
            );
        }).toList();
    }

    private ProductGroupKey createKey(ItemEntity item) {
        return new ProductGroupKey(ProductNameNormalizer.normalize(item.getGroceryName()),
                normalizeType(item.getGroceryType()),
                item.getSellingWeightKg(),
                item.getSellingVolumeL());
    }

    private String normalizeType(String groceryType) {
        if (groceryType == null) {
            return " ";
        }
        return groceryType.toLowerCase().trim().replaceAll("\\s+", "");
    }

    private boolean needsReview(List<ItemEntity> items) {
        return items.stream()
                .collect(Collectors.groupingBy(ItemEntity::getStoreId, Collectors.counting()))
                .values()
                .stream()
                .anyMatch(count -> count > 1);

    }
}
