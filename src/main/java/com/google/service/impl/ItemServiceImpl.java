package com.google.service.impl;

import com.google.dao.ItemDOMapper;
import com.google.dao.ItemStockDOMapper;
import com.google.dataobject.ItemDO;
import com.google.dataobject.ItemStockDO;
import com.google.error.BusinessException;
import com.google.error.EmBusinessError;
import com.google.service.ItemService;
import com.google.service.model.ItemModel;
import com.google.validator.ValidationResult;
import com.google.validator.ValidatorImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ItemServiceImpl implements ItemService {

    @Autowired
    private ValidatorImpl validator;

    @Autowired
    private ItemDOMapper itemDOMapper;

    @Autowired
    private ItemStockDOMapper itemStockDOMapper;

    @Override
    @Transactional
    public ItemModel createItem(ItemModel itemModel) throws BusinessException {
        // 校验入参
        ValidationResult result = validator.validate(itemModel);

        if (result.isHasErrors()) {
            throw new BusinessException(EmBusinessError.PARAMETER__VALIDATION_ERROR, result.getErrMsg());
        }

        // 转化itemModel->dataobject
        ItemDO itemDO = this.convertFromItemModel(itemModel);

        // 写入数据库
        itemDOMapper.insertSelective(itemDO);
        itemModel.setId(itemDO.getId());

        ItemStockDO itemStockDO = this.convertStockFromItemModel(itemModel);

        itemStockDOMapper.insertSelective(itemStockDO);
        // 返回创建完成的对象
        return this.getItemById(itemModel.getId());
    }

    private ItemDO convertFromItemModel(ItemModel itemModel) {
        if (itemModel == null) {
            return null;
        }
        ItemDO itemDO = new ItemDO();
        BeanUtils.copyProperties(itemModel, itemDO);
        return itemDO;
    }





    private ItemStockDO convertStockFromItemModel(ItemModel itemModel) {
        if (itemModel == null) {
            return null;
        }

        ItemStockDO itemStockDO = new ItemStockDO();
        itemStockDO.setStock(itemModel.getStock());
        itemStockDO.setItemId(itemModel.getId());
        return itemStockDO;
    }

    @Override
    public List<ItemModel> listItem() {

        List<ItemDO> itemModelList = itemDOMapper.listItem();

        List<ItemModel> collect = itemModelList.stream().map(itemDO -> {
            ItemStockDO itemStockDO = itemStockDOMapper.selectItemId(itemDO.getId());
            ItemModel itemModel = this.convertFromDataObject(itemDO, itemStockDO);
            return itemModel;
        }).collect(Collectors.toList());

        return collect;
    }

    @Override
    public ItemModel getItemById(Integer id) {
        ItemDO itemDO = itemDOMapper.selectByPrimaryKey(id);
        if (itemDO == null) {
            return null;
        }

        // 操作获得库存数量
        ItemStockDO itemStockDO = itemStockDOMapper.selectItemId(itemDO.getId());

        // dataobject->model
        ItemModel itemModel = convertFromDataObject(itemDO, itemStockDO);

        return itemModel;
    }

    private ItemModel convertFromDataObject(ItemDO itemDO, ItemStockDO itemStockDO) {
        ItemModel itemModel = new ItemModel();

        BeanUtils.copyProperties(itemDO, itemModel);
        itemModel.setStock(itemStockDO.getStock());

        return itemModel;
    }
}
