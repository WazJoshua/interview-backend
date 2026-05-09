COMMENT
ON COLUMN document_chunks.embedding
    IS '2048 维向量嵌入（Provider text-embedding-v4；存储列为 vector(2048)，ANN 检索使用 halfvec(2048) 表达式索引）';
