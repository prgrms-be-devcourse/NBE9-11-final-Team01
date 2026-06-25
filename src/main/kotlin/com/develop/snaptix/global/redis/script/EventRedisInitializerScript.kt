package com.develop.snaptix.global.redis.script

const val EVENT_REDIS_INITIALIZE_SCRIPT =
    """
        local writtenKeys = {}

        local function remember(key)
          table.insert(writtenKeys, key)
        end

        local ok, err = pcall(function()
          local ttlSeconds = tonumber(ARGV[1])
          local groupName = ARGV[2]
          local cacheJson = ARGV[3]

          redis.call('SET', KEYS[1], cacheJson)
          remember(KEYS[1])
          redis.call('EXPIRE', KEYS[1], ttlSeconds)

          local groupResult = redis.pcall('XGROUP', 'CREATE', KEYS[2], groupName, '$', 'MKSTREAM')
          if type(groupResult) == 'table' and groupResult.err then
            if not string.find(groupResult.err, 'BUSYGROUP') then
              error(groupResult.err)
            end
          else
            remember(KEYS[2])
          end

          local stockCount = tonumber(ARGV[4])
          local argIndex = 5

          for i = 1, stockCount do
            local stockKey = KEYS[i + 2]
            redis.call('SET', stockKey, ARGV[argIndex])
            remember(stockKey)
            argIndex = argIndex + 1
          end
        end)

        if not ok then
          for _, key in ipairs(writtenKeys) do
            redis.call('DEL', key)
          end
          error(err)
        end

        return 'OK'
        """
