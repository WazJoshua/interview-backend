# Configuration Reference

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| API_KEY | Yes | - | Your API key |
| API_URL | No | https://api.example.com | API endpoint |
| TIMEOUT | No | 30000 | Request timeout in ms |
| RETRY_COUNT | No | 3 | Number of retries |

## Database Settings

| Setting | Type | Description |
|---------|------|-------------|
| host | String | Database host |
| port | Integer | Database port |
| name | String | Database name |
| pool_size | Integer | Connection pool size |