#!/usr/bin/env python3
"""
Script to replace endpoint URLs with API IDs in ACR JSON files.
"""
import json
import os
import re
from pathlib import Path

# Mapping of endpoint patterns to API IDs
ENDPOINT_TO_API_ID = {
    # Enrolment Store Proxy
    r'/enrolment-store-proxy/enrolment-store/groups': 'ES3',
    r'/enrolment-store-proxy/enrolment-store/enrolments/[^/]+/groups': 'ES1',
    r'/enrolment-store-proxy/enrolment-store/users/[^/]+/enrolments': 'ES2',
    r'/enrolment-store-proxy/enrolment-store/enrolments/[^/]+/allocated-principal-users': 'ES19',
    r'/enrolment-store-proxy/enrolment-store/enrolments': 'ES8',
    
    # ETMP
    r'/etmp/RESTAdapter/rosm/agent-relationship': 'ETMP03',  # Default, will be refined by query params
    r'/etmp/RESTAdapter/itsa/taxpayer/business-details': 'ETMP05',  # Also could be ETMP06 or ETMP07
    
    # Agent Permissions  
    r'/agent-permissions/arn/[^/]+/client/[^/]+/groups': 'AP06',
    r'/agent-permissions/arn/[^/]+/user/[^/]+/clients': 'AP13',
    r'/agent-permissions/arn/[^/]+/client/[^/]+/user/[^/]+': 'AP16',
    
    # Agent User Client Details
    r'/agent-user-client-details/arn/[^/]+/client/[^/]+/user/[^/]+': 'AUCD09',
    r'/agent-user-client-details/arn/[^/]+/user-check': 'AUCD04',
    r'/agent-user-client-details/arn/[^/]+/client/[^/]+': 'AUCD08',
    r'/agent-user-client-details/arn/[^/]+/work-items-exist': 'AUCD16',
    r'/agent-user-client-details/arn/[^/]+/cache-refresh': 'AUCD18',
    
    # Agent FI Relationship
    r'/agent-fi-relationship/relationships/agent/[^/]+/service/[^/]+/client/[^/]+': 'AFR01',
    r'/agent-fi-relationship/relationships': 'AFR02',
    r'/agent-fi-relationship/relationships/agent/[^/]+/service/[^/]+/client/[^/]+': 'AFR03',
    
    # Agent Assurance
    r'/agent-assurance/agent-record': 'AA27',
    r'/agent-assurance/acceptableNumberOfClients/utr/[^/]+/agentCode/[^/]+': 'AA04',
    
    # Agent Mapping
    r'/agent-mapping/mappings/sa/[^/]+': 'AM09',
    r'/agent-mapping/mappings/[^/]+': 'AM08',
    
    # DES
    r'/registration/relationship/nino/[^/]+': 'DES08',
    r'/sa/agents/nino/[^/]+': 'DES08',
    r'/vat/customer/vrn/[^/]+/information': 'DES09',
    r'/registration/business-details/utr/[^/]+': 'DES05',
    
    # IF
    r'/individuals/details/nino/[^/]+': 'ETMP06',  # Actually ETMP via HIP
    r'/organisations/trust/[^/]+': 'IF01',
    r'/plastic-packaging-tax/subscriptions/[^/]+/status': 'IF02',
    r'/plastic-packaging-tax/subscriptions/[^/]+': 'IF03',
    r'/pillar2/subscription/[^/]+': 'IF04',
    r'/trusts/agent-known-fact-check/[^/]+/[^/]+': 'IF05',
    r'/dac6/dct50d/v1': 'IF06',
    
    # Citizen Details
    r'/citizen-details/[^/]+/designatory-details': 'CD01',
    r'/citizen-details/nino-no-suffix/[^/]+': 'CD03',
    r'/citizen-details/[^/]+': 'CD02',
    
    # Users Groups Search
    r'/users-groups-search/groups/[^/]+/users': 'UGS01',
    r'/users-groups-search/groups/[^/]+': 'UGS02',
    
    # Email
    r'/hmrc/email': 'EMAIL01',
}

def normalize_endpoint(endpoint):
    """Normalize endpoint by replacing path parameters with generic placeholder."""
    # Replace :param with [^/]+
    normalized = re.sub(r':[a-zA-Z0-9_]+', '[^/]+', endpoint)
    return normalized

def find_matching_api_id(endpoint):
    """Find matching API ID for an endpoint."""
    normalized = normalize_endpoint(endpoint)
    
    for pattern, api_id in ENDPOINT_TO_API_ID.items():
        if re.match(pattern, normalized):
            return api_id
    
    return None

def process_json_file(filepath):
    """Process a single JSON file to replace endpoints with API IDs."""
    with open(filepath, 'r') as f:
        data = json.load(f)
    
    modified = False
    
    # Process interactions
    if 'interactions' in data:
        for interaction in data['interactions']:
            if 'endpoint' in interaction:
                endpoint = interaction['endpoint']
                api_id = find_matching_api_id(endpoint)
                
                if api_id:
                    # Replace endpoint with apiId
                    del interaction['endpoint']
                    interaction['apiId'] = api_id
                    modified = True
                    print(f"  {filepath.name}: Step {interaction.get('step', '?')} - {endpoint} → {api_id}")
    
    # Process externalDependencies
    if 'externalDependencies' in data:
        for dep in data['externalDependencies']:
            if 'endpoint' in dep:
                endpoint = dep['endpoint']
                api_id = find_matching_api_id(endpoint)
                
                if api_id:
                    # Replace endpoint with apiId
                    del dep['endpoint']
                    dep['apiId'] = api_id
                    modified = True
                    print(f"  {filepath.name}: Dependency '{dep.get('name', '?')}' - {endpoint} → {api_id}")
    
    if modified:
        # Write back with proper formatting
        with open(filepath, 'w') as f:
            json.dump(data, f, indent=2)
        return True
    
    return False

def main():
    base_dir = Path(__file__).parent
    
    total_files = 0
    modified_files = 0
    
    print("Processing ACR JSON files...\n")
    
    # Process all ACR directories
    for i in range(1, 35):
        acr_dir = base_dir / f"ACR{i:02d}"
        json_file = acr_dir / f"ACR{i:02d}.json"
        
        if json_file.exists():
            total_files += 1
            if process_json_file(json_file):
                modified_files += 1
    
    print(f"\n✓ Processed {total_files} files")
    print(f"✓ Modified {modified_files} files")

if __name__ == '__main__':
    main()
