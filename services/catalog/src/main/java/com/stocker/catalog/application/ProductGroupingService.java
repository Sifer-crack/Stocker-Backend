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

    public Map<ProductGroupKey, List<ItemEntity>> getAllGroups() {
        List<ItemEntity> items = itemRepository.findAll();

        return items.stream().collect(Collectors.groupingBy(this::createKey));
    }

    private ProductGroupKey createKey(ItemEntity item) {
        return new ProductGroupKey(ProductNameNormalizer.normalize(item.getGroceryName()),
                normalizeType(item.getGroceryType()),
                item.getSellingWeightKg(),
                item.getSellingVolumeL());
    }

    private String normalizeType(String groceryType) {
        if (groceryType == null) {
            return "";
        }
        return groceryType.toLowerCase().trim().replaceAll("\\s+", "");
    }

    public boolean isSafeGroup(List<ItemEntity> items) {
        return !needsReview(items);
    }

    private boolean needsReview(List<ItemEntity> items) {

        // if the supplied item data already says an item needs review, don't treat as safe
        boolean sourceItemNeedsReview = items.stream().anyMatch(ItemEntity::isNeedsReview);

        if (sourceItemNeedsReview) {
            return true;
        }

        // more than one matching item from the same store might mean duplicate items or other issues
        return items.stream()
                .collect(Collectors.groupingBy(ItemEntity::getStoreId, Collectors.counting()))
                .values()
                .stream()
                .anyMatch(count -> count > 1);

    }

    // previews what will be imported as a canonical item inside pantry
    public List<ProductImportPreview> previewImport() {
        return getAllGroups()
                .entrySet()
                .stream()
                .filter(entry -> isSafeGroup(entry.getValue()))
                .map(entry -> {
                    ProductGroupKey key = entry.getKey();
                    List<ItemEntity> items = entry.getValue();

                    List<Integer> itemIds = items.stream()
                            .map(ItemEntity::getId)
                            .sorted()
                            .toList();

                    return new ProductImportPreview(
                            key.normalizedName(),
                            key.groceryType(),
                            key.weightKg(),
                            key.volumeL(),
                            items.size(),
                            itemIds
                            );
                }).sorted((a, b) -> a.productName().compareTo(b.productName())).toList();


    }
}
